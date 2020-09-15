package edu.colorado.plv.bounder.symbolicexecutor

import java.util

import edu.colorado.plv.bounder.ir.{CallinMethodReturn, CmdWrapper, IRWrapper, Loc}
import edu.colorado.plv.bounder.symbolicexecutor.state.{BottomQry, PathNode, Qry, SomeQry, State}
import edu.colorado.plv.fixedsoot.EnhancedUnitGraphFixed
import soot.{Body, UnitPatchingChain}

import scala.collection.mutable
import scala.jdk.CollectionConverters._

case class SymbolicExecutorConfig[M,C](stepLimit: Option[Int],
                                       w :  IRWrapper[M,C],
                                       c : ControlFlowResolver[M,C],
                                       transfer : TransferFunctions[M,C]
                                      )
class SymbolicExecutor[M,C](config: SymbolicExecutorConfig[M,C]) {
//  val controlFlowResolver = new ControlFlowResolver[M,C](w)
  /**
   *
   * @param qry - a source location and an assertion to prove
   * @return None if the assertion at that location is unreachable, Some(Trace) if it is reachable.
   *         Trace will contain a tree of backward executions for triage.
   *         // TODO; Config to exclude proven
   */
  def executeBackward(qry: Qry) : Set[PathNode] = {
//    if(stepLimit > 0) {
//      val predStates: Set[Qry] = activeqry.map(qry => executeStep(qry))
//      predStates.map(a => PathNode(a,source))
//    }else{
//      // Exceeded number of steps, terminate search
//      activeQueries.map(a => PathNode(a,source))
//    }
    config.stepLimit match{
      case Some(limit) => executeBackwardLimitKeepAll(Set(PathNode(qry,None)),limit)
      case None =>
        ???
    }
  }
  def executeBackwardLimitKeepAll(qrySet: Set[PathNode], limit:Int,
                                  refuted: Set[PathNode] = Set()):Set[PathNode] = {
    if(qrySet.isEmpty){
      refuted
    }else if(limit > 0) {
      val nextQry = qrySet.flatMap( a => a match{
        case succ@PathNode(qry@SomeQry(_,_), _) => executeStep(qry).map(PathNode(_,Some(succ)))
        case PathNode(BottomQry(_), _) => Set()
      })
      executeBackwardLimitKeepAll(nextQry, limit - 1, qrySet.filter(_.qry.isInstanceOf[BottomQry]))
    }else {
      refuted ++ qrySet
    }
  }

  /**
   * Call methods to choose where to go with symbolic execution and apply transfer functions
   * @param qry location and state combination
   * @return
   */
  def executeStep(qry:Qry):Set[Qry] = qry match{
    case SomeQry(state, loc) =>
      println(s"location: ${loc})")
      println(s"state: $state")
      println("-------------")
      val predecessorLocations: Seq[Loc] = config.c.resolvePredicessors(loc,state)
      predecessorLocations.flatMap(l => {
        val newStates = config.transfer.transfer(state,l,loc)
        newStates.map(state => state.simplify match {
          case Some(state) => SomeQry(state, l)
          case None => BottomQry(l)
        })
//          if(state.isFeasible) SomeQry(state,l) else BottomQry(l))
      }).toSet
    case BottomQry(_) => Set()
  }
}