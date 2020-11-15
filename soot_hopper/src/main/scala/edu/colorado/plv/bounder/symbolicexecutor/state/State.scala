package edu.colorado.plv.bounder.symbolicexecutor.state

import edu.colorado.hopper.solver.StateSolver
import edu.colorado.plv.bounder.ir.{FieldRef, LVal, LocalWrapper, ParamWrapper, RVal}
import edu.colorado.plv.bounder.lifestate.LifeState.{I, LSPred}


object State {
  private var id:Int = -1
  def getId():Int = {
    id = id + 1
    id
  }
//  val solver = new Z3StateSolver()
}

// pureFormula is a conjunction of constraints
// callStack is the call string from thresher paper
sealed trait TraceAbstraction
case class AbsFormula(pred:LSPred) extends TraceAbstraction
case class AbsArrow(traceAbstraction: TraceAbstraction, i:I) extends TraceAbstraction
case class AbsAnd(t1 : TraceAbstraction, t2:TraceAbstraction) extends TraceAbstraction
case class AbsEq(lsVar : String, pureVar: PureVar) extends TraceAbstraction

//case class LSAbstraction(pred: LSPred, bind : Map[String, PureExpr]) extends TraceAbstraction {
//  override def toString: String = s"[${pred.toString} , ${bind.toString}]"
//}
//case class Reg(v: PureVar) extends TraceAbstraction
//case object TopTraceAbstraction extends TraceAbstraction

case class State(callStack: List[CallStackFrame], heapConstraints: Map[HeapPtEdge, PureExpr],
                 pureFormula: Set[PureConstraint], traceAbstraction: Set[TraceAbstraction]) {
  override def toString:String = {
    val stackString = callStack.headOption match{
      case Some(sf) => {

        val locals: Map[StackVar, PureExpr] = sf.locals
        sf.methodLoc.toString() + " locals: " + locals.map(k => k._1.toString + " -> " + k._2.toString).mkString(",")
      }
      case None => "[nc]"
    }
    val heapString = s"   heap: ${heapConstraints.map(a => a._1.toString + "->" +  a._2.toString).mkString(" * ")}"
    val pureFormulaString = "   pure: " + pureFormula.map(a => a.toString).mkString(" && ")
    val traceString = s"   trace: ${traceAbstraction.mkString(" * ")}"
    s"($stackString $heapString   $pureFormulaString $traceString)"
  }
  def simplify[T](solver : StateSolver[T]):Option[State] = {
//    val solver = new Z3Solver()
    //TODO: garbage collect abstract variables and heap cells not reachable from the trace abstraction or spec
    solver.push()
    val simpl = solver.simplify(this)
    solver.pop()
    simpl
  }

  // helper functions to find pure variable
  private def expressionContains(expr: PureExpr, pureVar: PureVar):Boolean = expr match {
    case p2@PureVar() => pureVar == p2
    case _ => false
  }
  private def callStackContains(p :PureVar):Boolean = {
    callStack.exists({
      case CallStackFrame(_,_,locals) => locals.exists(r => expressionContains(r._2,p))
    })
  }

  private def ptEdgeContains(edge: HeapPtEdge, p: PureVar): Boolean = edge match{
    case FieldPtEdge(p2, _) => p == p2
    case _ => ???
  }

  private def heapContains(p:PureVar):Boolean =
    heapConstraints.exists(r => expressionContains(r._2,p) || ptEdgeContains(r._1,p))

  private def pureFormulaContains(p: PureVar): Boolean =
    pureFormula.exists(c => expressionContains(c.lhs,p) || expressionContains(c.rhs,p))

  def traceAbstractionContains(p: PureVar): Boolean = {
    def iTraceAbstractionContains(t: TraceAbstraction, p: PureVar): Boolean = t match{
      case AbsEq(_,pureVar) => p == pureVar
      case AbsAnd(t1,t2) => iTraceAbstractionContains(t1,p) || iTraceAbstractionContains(t2,p)
      case AbsArrow(t1, _) => iTraceAbstractionContains(t1,p)
      case AbsFormula(_) => false
    }
    traceAbstraction.exists(iTraceAbstractionContains(_, p))
  }

  def contains(p:PureVar):Boolean = {
     callStackContains(p) || heapContains(p) || pureFormulaContains(p) || traceAbstractionContains(p)
  }
  def getLocal(l:LVal):Option[PureExpr] = l match {
    case LocalWrapper(name,localType) => {
      callStack match{
        case CallStackFrame(_,_,locals)::_ => locals.get(StackVar(name))
        case Nil => None
      }
    }
    case _ => ???
  }
  // TODO: rename getOrDefineClass
  def getOrDefine(l : LVal): (PureVar,State) = (l,callStack) match{
    case (LocalWrapper(name,localType), cshead::cstail) =>
      cshead.locals.get(StackVar(name)) match {
        case Some(v@PureVar()) => (v, this)
        case None => val newident = PureVar();
          (newident, State(
            callStack = cshead.copy(locals = cshead.locals + (StackVar(name) -> newident)) :: cstail,
            heapConstraints,
//            typeConstraints + (newident -> TypeConstraint.fromLocalType(localType)),
            pureFormula,traceAbstraction // TODO: reg purevar
          ))
      }
    case _ =>
      ???
  }

  /**
   * When a var is assigned, we remove it from our constraint set
   * @param l variable being assigned
   * @return new state
   */
  def clearLVal(l : LVal): State = (l,callStack) match {
    case (LocalWrapper(name,localType), cshead::cstail) =>
      State(cshead.removeStackVar(StackVar(name))::cstail,heapConstraints, pureFormula, traceAbstraction)
    case _ =>
      ???
  }

  def clearPureVar(p:PureVar):State = {
    ???
  }

  def addHeapEdge(fr:FieldRef, pv:PureVar): State = {
    ???
  }
  def pureVars():Set[PureVar] = {
    val pureVarOpt = (a:PureExpr) => a match {
      case p: PureVar => Some(p)
      case _ => None
    }
    val pureVarFromLocals: Set[PureVar] = callStack.headOption match {
      case Some(CallStackFrame(_, _, locals)) =>

        locals.flatMap(a => pureVarOpt(a._2)).toSet
      case None => Set()
    }
    val pureVarFromHeap = heapConstraints.flatMap(a => pureVarOpt(a._2)).toSet
    pureVarFromHeap ++ pureVarFromLocals
  }
  def isNull(pv:PureVar):Boolean = {
    pureFormula.contains(PureConstraint(pv,Equals,NullVal))
  }
  def newPureVarType(p:PureVar,newType:String): State =
    ???
}

sealed trait Val
//case object TopVal extends Val
//case object NullVal extends Val
//
///**
// * Val that is instantiated from a subtype of className
// */
//case class ObjSubtypeVal(className:String) extends Val{
//  val id = State.getId()
//}

sealed trait Var

case class StackVar(name : String) extends Var{
  override def toString:String = name
}

sealed trait CmpOp
case object MayEqual extends CmpOp{
  override def toString:String = "?="
}
case object Equals extends CmpOp{
  override def toString:String = " == "
}
case object NotEquals extends CmpOp{
  override def toString:String = " != "
}
case object TypeComp extends CmpOp{
  override def toString:String = " : "
}

case class PureConstraint(lhs:PureExpr, op: CmpOp, rhs:PureExpr) {
  override def toString:String = s"$lhs $op $rhs"
}

sealed abstract class PureExpr {
  def substitute(toSub : PureExpr, subFor : PureVar) : PureExpr
  def isStringExpr : Boolean = false
  def isBitwiseExpr : Boolean = false
  def isFloatExpr : Boolean = false
  def isLongExpr : Boolean = false
  def isDoubleExpr : Boolean = false
  def getVars(s : Set[PureVar] = Set.empty) : Set[PureVar]
}




// primitive values
sealed abstract class PureVal(val v : Any) extends PureExpr with Val {
  override def substitute(toSub : PureExpr, subFor : PureVar) : PureVal = this

  def >(p : PureVal) : Boolean = sys.error("GT for arbitrary PureVal")
  def <(p : PureVal) : Boolean = sys.error("LT for arbitrary PureVal")
  def >=(p : PureVal) : Boolean = sys.error("GE for arbitrary PureVal")
  def <=(p : PureVal) : Boolean = sys.error("LE for arbitrary PureVal")
  override def isStringExpr : Boolean = ??? //this.isInstanceOf[StringVal]
  override def getVars(s : Set[PureVar] = Set.empty) : Set[PureVar] = s

//  override def hashCode : Int = ???
//  override def equals(other : Any) : Boolean = other match {
//    case p : PureVal => this.v == p.v
//    case _ => false
//  }
  override def toString : String = v.toString
}
case object NullVal extends PureVal{
  override def toString:String = "NULL"
}
//TODO: do we need type constraints here?
sealed trait TypeConstraint extends PureVal
case class SubclassOf(clazz: String) extends TypeConstraint
case class SuperclassOf(clazz:String) extends TypeConstraint
case class ClassType(typ:String) extends TypeConstraint {
  override def toString:String = s"ClassIs($typ)"
}

// pure var is a symbolic var (e.g. this^ from the paper)
sealed case class PureVar() extends PureExpr with Val {
  val id : Int = State.getId()
  override def getVars(s : Set[PureVar]) : Set[PureVar] = s + this

  override def substitute(toSub : PureExpr, subFor : PureVar) : PureExpr = if (subFor == this) toSub else this

  override def hashCode : Int = id*100271
  override def equals(other : Any) : Boolean = other match {
    case p : PureVar => this.id == p.id
    case _ => false
  }
  override def toString : String = "p-" + id
}

