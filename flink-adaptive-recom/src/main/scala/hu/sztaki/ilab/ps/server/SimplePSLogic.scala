package hu.sztaki.ilab.ps.server

import hu.sztaki.ilab.ps.{ParameterServer, ParameterServerLogic}

import scala.collection.mutable

class SimplePSLogic[P](paramInit: => Int => P, paramUpdate: => (P, P) => P) extends ParameterServerLogic[P, (Int, P)] {
  val params = new mutable.HashMap[Integer, P]()

  @transient lazy val init: (Int) => P = paramInit
  @transient lazy val update: (P, P) => P = paramUpdate

  override def onPullRecv(id: Int, workerPartitionIndex: Int, ps: ParameterServer[P, (Int, P)]): Unit =
    ps.answerPull(id, params.getOrElseUpdate(id, init(id)), workerPartitionIndex)

  override def onPushRecv(id: Int, deltaUpdate: P, workerPartitionIndex: Int, ps: ParameterServer[P, (Int, P)]): Unit
  = {
    val c = params.get(id) match {
      case Some(q) =>
        update(q, deltaUpdate)
      case None =>
        throw new IllegalStateException("Not existed model was not able to update by any delta.")
    }
    params += ((id, c))
    ps.output((id, c))
  }
}
