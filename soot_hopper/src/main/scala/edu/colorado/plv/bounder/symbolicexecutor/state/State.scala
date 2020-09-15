package edu.colorado.plv.bounder.symbolicexecutor.state

import edu.colorado.plv.bounder.ir.{FieldRef, IRWrapper, LVal, LocalWrapper, ParamWrapper, RVal}
import edu.colorado.plv.bounder.solver.Z3Solver


object State {
  private var id:Int = -1
  def getId():Int = {
    id = id + 1
    id
  }
  val solver = new Z3Solver()
}

// pureFormula is a conjunction of constraints
// callStack is the call string from thresher paper
case class State(callStack: List[CallStackFrame], heapConstraints: Map[HeapPtEdge, Val],
                 pureFormula: Set[PureConstraint]) {
  override def toString:String = {
    val stackString = callStack.headOption match{
      case Some(sf) => {

        val locals: Map[StackVar, Val] = sf.locals
        sf.methodLoc.toString() + " locals: " + locals.map(k => k._1.toString + " -> " + k._2.toString).mkString(",")
      }
      case None => "[nc]"
    }
    val heapString = s"   heap: ${heapConstraints.map(a => a._1.toString + "->" +  a._2.toString).mkString(" * ")}"
    val pureFormulaString = "   pure: " + pureFormula.map(a => a.toString).mkString(" && ")
    s"($stackString $heapString   $pureFormulaString)"
  }
  def simplify:Option[State] = {
    val solver = new Z3Solver()
    solver.simplify(this)
  }
  def isDefined(l:LVal):Boolean = l match{
    case LocalWrapper(name,localType) => {
      callStack match{
        case CallStackFrame(_,_,locals)::_ => locals.contains(StackVar(name, localType))
        case Nil => false
      }
    }
    case _ => ???
  }
  def getOrDefine(l : LVal): (Val,State) = (l,callStack) match{
    case (LocalWrapper(name,localType), cshead::cstail) =>
      cshead.locals.get(StackVar(name,localType)) match {
        case Some(v) => (v, this)
        case None => val newident = PureVar();
          (newident, State(
            callStack = cshead.copy(locals = cshead.locals + (StackVar(name,localType) -> newident)) :: cstail,
            heapConstraints,
//            typeConstraints + (newident -> TypeConstraint.fromLocalType(localType)),
            pureFormula
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
      State(cshead.removeStackVar(StackVar(name, localType))::cstail,heapConstraints, pureFormula)
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
    val pureVarOpt = (a:Val) => a match {
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
case class StackVar(name : String, varType:String) extends Var{
  override def toString:String = name
}

sealed trait CmpOp
case object Equals extends CmpOp{
  override def toString:String = " == "
}
case object NotEquals extends CmpOp{
  override def toString:String = " != "
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
case class ClassVal(typ:String) extends PureVal

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

object TypeConstraint{
  def fromLocalType(typeName:String):TypeConstraint = typeName match {
    case v if !v.contains(".") =>
      throw new IllegalArgumentException(s"unimplemented primitive type $v")
    case s => SubClassOf(s)
  }
}
sealed trait TypeConstraint
case class SubClassOf(clazz:String) extends TypeConstraint