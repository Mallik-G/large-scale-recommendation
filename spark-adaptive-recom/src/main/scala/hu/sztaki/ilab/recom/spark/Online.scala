package hu.sztaki.ilab.recom.spark

import hu.sztaki.ilab.recom.spark.Online.{LocallyCheckpointedRDD, NotCheckpointedRDD, PossiblyCheckpointedRDD}
import org.apache.spark._
import org.apache.spark.mllib.recommendation.ALS
import org.apache.spark.rdd._
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming._
import org.apache.spark.streaming.dstream._
import hu.sztaki.ilab.recom.core._

import scala.collection.mutable
import scala.reflect.ClassTag
import scalaz.Scalaz
import Online._
import hu.sztaki.ilab.recom.core.{FactorInitializerDescriptor, FactorUpdater}
import OfflineSpark.Vector

class Online[QI: ClassTag, PI: ClassTag](
  @transient protected val ratings: DStream[Rating[QI, PI]])(
  bucketLowerBound: Int = 50,
  bucketUpperBound: Int = 1000,
  nPartitions: Int = 20,
  mapInitializer: mutable.Map[Int, Array[Double]] = mutable.HashMap.empty)
extends Serializable {
  case class UserVectorUpdate(ID: QI, vec: Array[Double])
  case class ItemVectorUpdate(ID: PI, vec: Array[Double])

  @transient protected var Q: PossiblyCheckpointedRDD[Vector[QI]] = _
  @transient protected var P: PossiblyCheckpointedRDD[Vector[PI]] = _

  def queryVectors = Q
  def probeVectors = P

  def ?(queries: DStream[QI], k: Int = 10,
        threshold: Double = 0.5): DStream[(QI, Seq[(PI, Double)])] = {
    queries.transform {
      dd =>
        val effectiveQ = Q.get.join(dd.map((_, null))).map(q => (q._1, q._2._1))
        this ? (effectiveQ, k, threshold)
    }
  }

  def ?(query: List[QI], k: Int, threshold: Double): Array[(QI, Seq[(PI, Double)])] = {
    this ? (Q.get.filter(q => query.contains(q._1)).cache(), k, threshold)
  }.collect()

  protected def ?(snapshotQ: RDD[(QI, Array[Double])],
                  k: Int, threshold: Double): RDD[(QI, Seq[(PI, Double)])] = {
    val snapshotP = P.get.repartition(nPartitions).cache()
    if (snapshotQ.count() == 0) {
      println(s"Queries specified was not found!")
      snapshotP.context.emptyRDD[(QI , Seq[(PI, Double)])]
    } else {
      snapshotP
        .map {
          case (i, p) =>
            val length: Length = Math.sqrt(p.map(v => Math.pow(v, v)).sum)
            val normalized = p.map(_ / length)
            (i, p, length, normalized)
        }
        .sortBy(-_._3)
        /**
          * Creating buckets on `P`.
          */
        .mapPartitions {
          partition =>
            Scalaz.unfold(partition) {
              iterator =>
                if (iterator.hasNext) {
                  val first = iterator.next
                  val maximal = first._3
                  var nElements = 1
                  val bucket = Some(
                    (List(first) ++ iterator.takeWhile {
                      case (_, _, length, _) =>
                        val expression =
                          (length > maximal * 0.9 && nElements < bucketUpperBound) ||
                            nElements < bucketLowerBound
                        nElements += 1
                        expression
                    }).zipWithIndex.map(
                      r =>
                        Bucket.Entry(
                          r._1._1, r._2, r._1._2, r._1._3, r._1._4
                        )
                    ) -> iterator
                  )
                  println(s"Created bucket with size [$nElements].")
                  bucket
                } else {
                  None
                }
            }.iterator
        }
        /**
          * Search phase.
          */
        .map {
          bucket =>
            (null, bucket)
        }
        /**
          * @todo Before this, the buckets should be cached.
          */
        .join(snapshotQ.map((null, _)))
        /**
          * Compute local threashold.
          */
        .map {
          case (_, (bucket: List[Bucket.Entry[PI]], (j, q))) =>
            val bucketLength = bucket.head.length
            val queryLength: Length = Math.sqrt(q.map(v => Math.pow(v, v)).sum)
            val localThreshold = threshold / (bucketLength * queryLength)
            ((j, q, localThreshold), bucket)
        }
          /**
            * Prune buckets based on local threshold.
            */
          .filter {
          case (((_, _, localThreshold), _)) =>
            localThreshold <= 1
        }
        .flatMap {
          case (((j, q, localThreshold), bucket)) =>
            val candidates = if (localThreshold == 1) {
              /**
                * Use cosine similarity search algorithm.
                */
              innerProductPruning(
                q,
                cosineSimilarityPruning(q, bucket, threshold),
                threshold
              )
            } else {
              /**
                * It must be lower than 1.
                * Use naive retrieval.
                */
              innerProductPruning(q, bucket, threshold)
            }
            candidates.map {
              case (pID, product) =>
                (j, pID, product)
            }
        }
        /**
          * @todo Should combine into size-capped container, instead of grouping everything together.
          */
        .groupBy(_._1)
        .map {
          group =>
            group._1 -> group._2.toSeq.sortBy(-_._3).take(k).map(x => x._2 -> x._3)
        }
    }
  }

  /**
    * This is the Length-Based Pruning.
    */
  private def cosineSimilarityPruning(
    q: Array[Double],
    bucket: List[Bucket.Entry[PI]],
    threshold: Double): List[Bucket.Entry[PI]] = {
    /**
      * @todo Precompute.
      */
    val qLength: Length = Math.sqrt(q.map(v => Math.pow(v, v)).sum)

    bucket.takeWhile {
      entry =>
        entry.length >= threshold / qLength
    }
  }

  private def innerProductPruning(q: Array[Double],
                                  bucket: List[Bucket.Entry[PI]],
                                  threshold: Double): List[(PI, Double)] = {
    bucket.flatMap {
      entry =>
        val product = entry.vector.zip(q).map(p => p._1 * p._2).sum
        if (product > threshold) {
          List(entry.ID -> product)
        } else {
          List.empty
        }
    }
  }

  def buildModelCombineOffline(ratings: DStream[Rating[QI, PI]],
                               factorInitializerForQI: FactorInitializerDescriptor[QI],
                               factorInitializerForPI: FactorInitializerDescriptor[PI],
                               factorUpdate: FactorUpdater,
                               parameters: Map[String, String],
                               checkpointEvery: Int,
                               offlineEvery: Int,
                               numberOfIterations: Int,
                               offlineAlgorithm: String,
                               numFactors: Int):
  DStream[Either[UserVectorUpdate, ItemVectorUpdate]] = {
    @transient val ssc = ratings.context

    @transient val users0: PossiblyCheckpointedRDD[Vector[QI]] =
      NotCheckpointedRDD(ssc.sparkContext.makeRDD(Seq()))
    @transient val items0: PossiblyCheckpointedRDD[Vector[PI]] =
      NotCheckpointedRDD(ssc.sparkContext.makeRDD(Seq()))

    Q = users0
    P = items0

    var ratingsHistory: PossiblyCheckpointedRDD[Rating[QI, PI]] =
      NotCheckpointedRDD(ssc.sparkContext.makeRDD(Seq.empty[Rating[QI, PI]]).cache())

    var checkpointCnt = checkpointEvery
    var offlineCnt = offlineEvery

    import OfflineSpark._

    val updates: DStream[Either[UserVectorUpdate, ItemVectorUpdate]] = ratings.transform { rs =>
      checkpointCnt -= 1
      val checkpointCurrent = checkpointCnt <= 0
      if (checkpointCurrent) {
        checkpointCnt = checkpointEvery
      }

      offlineCnt -= 1
      val offlineCurrent = offlineCnt <= 0
      if (offlineCurrent) {
        offlineCnt = offlineEvery
      }

      rs.cache()
      ratingsHistory = NotCheckpointedRDD(ratingsHistory.get.union(rs.cache()).cache())

      val (uUpdates, iUpdates) = if (!offlineCurrent) {
        // online updates

        val (userUpdates, itemUpdates) =
          offlineDSGDUpdatesOnly(rs, Q.get, P.get,
            factorInitializerForQI, factorInitializerForPI, factorUpdate,
            ssc.sparkContext.defaultParallelism, _.hashCode(), 1)

        def applyUpdatesAndCheckpointOrCache[I: ClassTag](
          oldRDD: PossiblyCheckpointedRDD[(I, Array[Double])],
          updates: RDD[(I, Array[Double])]):
        PossiblyCheckpointedRDD[(I, Array[Double])] = {
          // merging old values with updates
          // full outer join is needed because
          //   1. there might be vectors in oldRDD that has not been updated
          //   2. there might be updates for vectors not yet present in oldRDD
          val rdd = oldRDD.get.fullOuterJoin(updates)
            .map {
              case (id, (oldOpt, updatedOpt)) => (id, updatedOpt.getOrElse(oldOpt.get))
            }

          // checkpoint or cache
          val nextRDD = if (checkpointCurrent) {
            val persisted = rdd.persist(StorageLevel.DISK_ONLY)
              .localCheckpoint()
            LocallyCheckpointedRDD(persisted)
          } else {
            NotCheckpointedRDD(rdd.cache())
          }

          // clear old values if not checkpointed
          oldRDD match {
            case NotCheckpointedRDD(x) => x.unpersist()
            case _ => ()
          }
          updates.unpersist()

          nextRDD
        }

        Q = applyUpdatesAndCheckpointOrCache(Q, userUpdates)
        P = applyUpdatesAndCheckpointOrCache(P, itemUpdates)

        (userUpdates, itemUpdates)
      } else {
        // offline

        val (userUpdates, itemUpdates) = offlineAlgorithm match {
          case "DSGD" =>
            offlineDSGD(ratingsHistory.get,
              ssc.sparkContext.makeRDD(Seq.empty[FactorVector[QI]]),
              ssc.sparkContext.makeRDD(Seq.empty[FactorVector[PI]]),
              factorInitializerForQI, factorInitializerForPI, factorUpdate,
              ssc.sparkContext.defaultParallelism, _.hashCode(), numberOfIterations)
            /*
          case "ALS" =>
            val model = ALS.train(ratingsHistory.get.map {
              case hu.sztaki.ilab.recom.core.Rating(u,i,r) =>
                org.apache.spark.mllib.recommendation.Rating(u,i,r)
            }, numFactors, numberOfIterations, 0.1)

            (model.userFeatures.cache(), model.productFeatures.cache())
            */
        }

        val oldUserRDD = Q
        val oldItemRDD = P

        Q = NotCheckpointedRDD(userUpdates)
        P = NotCheckpointedRDD(itemUpdates)

        // clear old values if not checkpointed
        oldUserRDD match {
          case NotCheckpointedRDD(x) => x.unpersist()
          case _ => ()
        }
        oldItemRDD match {
          case NotCheckpointedRDD(x) => x.unpersist()
          case _ => ()
        }

        (userUpdates, itemUpdates)
      }

      val userUpdatesEither: RDD[Either[UserVectorUpdate, ItemVectorUpdate]] = uUpdates
        .map {
          case (i: QI, vector: Array[Double]) => Left(UserVectorUpdate(i, vector))
        }
      val itemUpdatesEither: RDD[Either[UserVectorUpdate, ItemVectorUpdate]] = iUpdates
        .map {
          case (i: PI, vector: Array[Double]) => Right(ItemVectorUpdate(i, vector))
        }

      userUpdatesEither.union(itemUpdatesEither)
    }

    updates
  }

  def buildModelWithMap(ratings: DStream[Rating[QI, PI]],
                        factorInitializerForQI: FactorInitializerDescriptor[QI],
                        factorInitializerForPI: FactorInitializerDescriptor[PI],
                        factorUpdate: FactorUpdater,
                        parameters: Map[String, String],
                        checkpointEvery: Int)
  : DStream[Either[Vector[QI], Vector[PI]]] = {
    @transient val ssc = ratings.context

    @transient val users0: PossiblyCheckpointedRDD[Vector[QI]] =
      NotCheckpointedRDD(ssc.sparkContext.makeRDD(Seq.empty[Vector[QI]]))
    @transient val items0: PossiblyCheckpointedRDD[Vector[PI]] =
      NotCheckpointedRDD(ssc.sparkContext.makeRDD(Seq.empty[Vector[PI]]))

    Q = users0
    P = items0

    var cnt = checkpointEvery

    import OfflineSpark._

    val updates = ratings.transform { rs =>
      cnt -= 1
      val checkpointCurrent = cnt <= 0
      if (checkpointCurrent) {
        cnt = checkpointEvery
      }

      val (userUpdates, itemUpdates) =
        offlineDSGDUpdatesOnly(rs, Q.get, P.get,
          factorInitializerForQI, factorInitializerForPI, factorUpdate,
          ssc.sparkContext.defaultParallelism, _.hashCode(), 1)

      def applyUpdatesAndCheckpointOrCache[I: ClassTag](
        oldRDD: PossiblyCheckpointedRDD[(I, Array[Double])],
        updates: RDD[(I, Array[Double])]):
      PossiblyCheckpointedRDD[(I, Array[Double])] = {
        // merging old values with updates
        val rdd = oldRDD.get.fullOuterJoin(updates)
          .map {
            case (id: I, (oldOpt: Option[Array[Double]], updatedOpt: Option[Array[Double]])) =>
              (id, updatedOpt.getOrElse(oldOpt.get))
          }

        // checkpoint or cache
        val nextRDD = if (checkpointCurrent) {
          val persisted = rdd.persist(StorageLevel.DISK_ONLY)
            .localCheckpoint()
          LocallyCheckpointedRDD(persisted)
        } else {
          NotCheckpointedRDD(rdd.cache())
        }

        // clear old values if not checkpointed
        oldRDD match {
          case NotCheckpointedRDD(x) => x.unpersist()
          case _ => ()
        }
        updates.unpersist()

        nextRDD
      }

      Q = applyUpdatesAndCheckpointOrCache(Q, userUpdates)
      P = applyUpdatesAndCheckpointOrCache(P, itemUpdates)

      // user updates are marked with true, while item updates with false
      userUpdates.map[Either[Vector[QI], Vector[PI]]](Left(_)).union(itemUpdates.map(Right(_)))
    }

    updates
  }
}

object Online {
  type Length = Double

  object Bucket extends Serializable {
    case class Entry[PI](
      ID: PI,
      bucketID: Int,
      vector: Array[Double],
      length: Double,
      normalized: Array[Double]
    )
  }
  sealed trait PossiblyCheckpointedRDD[A] {
    def get: RDD[A]
  }

  case class LocallyCheckpointedRDD[A](rdd: RDD[A])(implicit classTag: ClassTag[A])
    extends PossiblyCheckpointedRDD[A] {
    def get: RDD[A] = rdd
  }

  case class NotCheckpointedRDD[A](rdd: RDD[A])(implicit classTag: ClassTag[A])
    extends PossiblyCheckpointedRDD[A] {
    def get: RDD[A] = rdd
  }

  def main(args: Array[String]): Unit = {

    val conf = new SparkConf().setMaster("local[4]").setAppName("online")
    val ssc = new StreamingContext(conf, Seconds(1))

    val q = mutable.Queue(10, 20, 30)
      .map(x => ssc.sparkContext.makeRDD(x to x + 3, numSlices = 4))
    val xs = ssc.queueStream(q, oneAtATime = true)

    val s0 = ssc.sparkContext.makeRDD(Seq(-1, -2))

    def p(sRDD: RDD[Int], xRDD: RDD[Int]): RDD[Int] = {
      sRDD.union(xRDD)
    }

    val stateDStream = dstreamFold[Int, Int](xs)(s0, p)
    stateDStream.print()

    ssc.start()

    Thread.sleep(5000)

  }

  def dstreamFold[S, A](xs: DStream[A])
                       (s0: RDD[S], p: (RDD[S], RDD[A]) => RDD[S])
                       (implicit sClassTag: ClassTag[S], aClassTag: ClassTag[A]): DStream[S] = {
    var rddS = s0

    val dstreamS = xs.transform { rddXs =>
      rddS = p(rddS, rddXs)
      rddS
    }

    dstreamS
  }

  def dstreamFold2[S1, S2, A](xs: DStream[A])
                             (s0: (RDD[S1], RDD[S2]),
                              p: (RDD[S1], RDD[S2], RDD[A]) => (RDD[S1], RDD[S2]))
                             (implicit
                              s1ClassTag: ClassTag[S1],
                              s2ClassTag: ClassTag[S2],
                              aClassTag: ClassTag[A]): (DStream[S1], DStream[S2]) = {
    var (s1, s2) = s0

    val stateStream: DStream[Either[S1, S2]] = xs
      .transform { rddXs =>
        val (nextS1, nextS2) = p(s1, s2, rddXs)
        s1 = nextS1
        s2 = nextS2
        val eitherS1: RDD[Either[S1, S2]] = s1.map(Left(_))
        val eitherS2: RDD[Either[S1, S2]] = s2.map(Right(_))
        eitherS1.union(eitherS2)
      }

    var e: Either[Int, String] = null

    val s1Stream = stateStream.flatMap {
      case Left(x) => Some(x)
      case _ => None
    }

    val s2Stream = stateStream.flatMap {
      case Right(x) => Some(x)
      case _ => None
    }

    (s1Stream, s2Stream)
  }
}
