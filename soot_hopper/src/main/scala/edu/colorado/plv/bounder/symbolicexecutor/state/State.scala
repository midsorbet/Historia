package edu.colorado.plv.bounder.symbolicexecutor.state

import edu.colorado.plv.bounder.solver.StateSolver
import edu.colorado.plv.bounder.ir.{IRWrapper, IntConst, LVal, LocalWrapper, MessageType, RVal, StringConst}
import edu.colorado.plv.bounder.lifestate.LifeState.{And, I, LSAtom, LSFalse, LSPred, LSTrue, NI, Not, Or}
import edu.colorado.plv.bounder.symbolicexecutor.state.State.findIAF


object State {
  private var id:Int = -1
  def getId():Int = {
    id = id + 1
    id
  }
  def topState:State =
    State(Nil,Map(),Set(),Set(),0)

  def findIAF(messageType: MessageType, tuple: (String, String), pred: LSPred):Set[I] = pred match{
    case i@I(mt, sigs, _) if mt == messageType && sigs.contains(tuple) => Set(i)
    case NI(i1,i2) => Set(i1,i2).flatMap(findIAF(messageType, tuple, _))
    case And(l1,l2) => findIAF(messageType,tuple,l1).union(findIAF(messageType,tuple,l2))
    case Or(l1,l2) => findIAF(messageType,tuple,l1).union(findIAF(messageType,tuple,l2))
    case Not(l) => findIAF(messageType, tuple, l)
    case _ => Set()
  }
}

// pureFormula is a conjunction of constraints
// callStack is the call string from thresher paper
//sealed trait TraceAbstractionArrow
case class AbstractTrace(a:LSPred,rightOfArrow:List[I], modelVars: Map[String,PureExpr]){
  def addModelVar(v: String, pureVar: PureExpr): AbstractTrace = {
    assert(!modelVars.contains(v), s"model var $v already in trace abstraction.")
    this.copy(modelVars= modelVars + (v->pureVar))
  }

  override def toString:String = s"(${modelVars} - ${a.toString} |> ${rightOfArrow.mkString(";")})"
}

sealed trait LSParamConstraint{
  def optTraceAbs: Option[AbstractTrace]
}
case class LSPure(p: PureExpr) extends LSParamConstraint {
  override def optTraceAbs: Option[AbstractTrace] = None
}
case class LSModelVar(s:String, trace:AbstractTrace) extends LSParamConstraint {
  override def optTraceAbs: Option[AbstractTrace] = Some(trace)
}
object LSAny extends LSParamConstraint {
  override def optTraceAbs: Option[AbstractTrace] = None
}

case class State(callStack: List[CallStackFrame], heapConstraints: Map[HeapPtEdge, PureExpr],
                 pureFormula: Set[PureConstraint], traceAbstraction: Set[AbstractTrace], nextAddr:Int) {
  var isSimplified = false
  def setSimplified(): State = {
    isSimplified = true
    this
  }

  def nextPv() = (PureVar(nextAddr), this.copy(nextAddr = nextAddr+1))

  def lsVarConstraint(f: AbstractTrace, lsvar: String): Option[LSParamConstraint] = f match{
    case AbstractTrace(_,_,mv) => mv.get(lsvar).map(LSPure)
  }

  def findIFromCurrent(dir: MessageType, signature: (String, String)): Set[(I, List[LSParamConstraint])] = {
    traceAbstraction.flatMap(ar =>{
      val iset = findIAF(dir,signature,ar.a)
      iset.map(i => (i, i.lsVars.map(mv => ar.modelVars.get(mv).map(LSPure)
        .getOrElse(LSModelVar(mv,ar)))))
    })
  }


  private def pformulaVars(p:PureExpr):Set[PureVar] = p match{
    case p:PureVar => Set(p)
    case _ => Set()
  }
  private def tformulaVars(t : LSPred):Set[PureVar] = t match{
    case And(lhs,rhs) => tformulaVars(lhs).union(tformulaVars(rhs))
    case Or(lhs,rhs) => tformulaVars(lhs).union(tformulaVars(rhs))
    case Not(v) => tformulaVars(v)
    case _ => Set()
  }
  private def formulaVars(trace: AbstractTrace):Set[PureVar] = {
    tformulaVars(trace.a).union(trace.modelVars.flatMap{
      case (_,v) => pformulaVars(v)
    }.toSet)
  }
  def allTraceVar():Set[PureVar] = traceAbstraction.flatMap(formulaVars)

  def pvTypeUpperBound(v: PureVar): Option[String] = {
    pureFormula.flatMap{
      case PureConstraint(v2, TypeComp, SubclassOf(s)) if v==v2 => Some(s)
      case PureConstraint(v2, TypeComp, ClassType(s)) if v==v2 => Some(s)
      case _ => None
    }.headOption
  }

  override def toString:String = {
    val stackString = callStack.headOption match{
      case Some(sf) =>
        val locals: Map[StackVar, PureExpr] = sf.locals
        s"\nstack: ${callStack.map(f => f.methodLoc.msgSig.getOrElse("")).mkString(";")}\n locals: " + locals.map(k => k._1.toString + " -> " + k._2.toString).mkString(",")
      case None => "[nc]"
    }
    val heapString = s"   heap: ${heapConstraints.map(a => a._1.toString + "->" +  a._2.toString).mkString(" * ")}\n"
    val pureFormulaString = "   pure: " + pureFormula.map(a => a.toString).mkString(" && ") +"\n"
    val traceString = s"   trace: ${traceAbstraction.mkString(" ; ")}"
    s"($stackString $heapString   $pureFormulaString $traceString)"
  }
  def containsLocal(localWrapper: LocalWrapper):Boolean = {
    val sVar = StackVar(localWrapper.name)
    callStack.headOption.exists(f => f.locals.contains(sVar))
  }

  private def pureExprSwap[T<:PureExpr](oldPv : PureVar, newPv : PureVar, expr:T): T = expr match{
    case PureVar(id) if id==oldPv.id => newPv.asInstanceOf[T]
    case pv@PureVar(_) => pv.asInstanceOf[T]
    case pv: PureVal => pv.asInstanceOf[T]
  }
  private def stackSwapPv(oldPv : PureVar, newPv : PureVar, frame: CallStackFrame): CallStackFrame =
    frame.copy(locals = frame.locals.map{
      case (k,v) => (k->pureExprSwap(oldPv, newPv, v))
    })

  private def heapSwapPv(oldPv : PureVar, newPv : PureVar, hv: (HeapPtEdge, PureExpr)):(HeapPtEdge, PureExpr) = hv match{
    case (FieldPtEdge(pv, fieldName), pe) =>
      (FieldPtEdge(pureExprSwap(oldPv, newPv, pv), fieldName), pureExprSwap(oldPv, newPv, pe))
  }
  private def pureSwapPv(oldPv : PureVar, newPv : PureVar, pureConstraint: PureConstraint):PureConstraint = pureConstraint match{
    case PureConstraint(lhs, op, rhs) =>
      PureConstraint(pureExprSwap(oldPv, newPv, lhs),op, pureExprSwap(oldPv, newPv, rhs))
  }

  private def traceSwapPv(oldPv : PureVar, newPv : PureVar, tr: AbstractTrace):AbstractTrace = {
    val nmv = tr.modelVars.map{
      case (k,v) => (k,pureExprSwap(oldPv, newPv, v))
    }
    tr.copy(modelVars = nmv)
  }

  /**
   *
   * @param oldPv
   * @param newPv
   */
  def swapPv(oldPv : PureVar, newPv: PureVar):State = {
    this.copy(
      callStack = callStack.map(f => stackSwapPv(oldPv, newPv, f)),
      heapConstraints = heapConstraints.map(hc => heapSwapPv(oldPv, newPv, hc)),
      pureFormula = pureFormula.map(pf => pureSwapPv(oldPv, newPv, pf)),
      traceAbstraction = traceAbstraction.map(ta => traceSwapPv(oldPv,newPv, ta))
    )
  }
  def simplify[T](solver : StateSolver[T]):Option[State] = {
    //TODO: garbage collect abstract variables and heap cells not reachable from the trace abstraction or spec
    solver.push()
    val simpl = solver.simplify(this)
    solver.pop()
    simpl
  }

  // helper functions to find pure variable
  private def expressionContains(expr: PureExpr, pureVar: PureVar):Boolean = expr match {
    case p2@PureVar(_) => pureVar == p2
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
    traceAbstraction.exists(_.modelVars.exists{
      case (k,v) if v == p => true
      case _ => false
    })
  }

  def contains(p:PureVar):Boolean = {
     callStackContains(p) || heapContains(p) || pureFormulaContains(p) || traceAbstractionContains(p)
  }
  // If an RVal exists in the state, get it
  // for a field ref, e.g. x.f if x doesn't exist, create x
  // if x.f doesn't exist and x does
  def get(l:RVal):Option[PureExpr] = l match {
    case LocalWrapper(name,_) =>
      callStack match{
        case CallStackFrame(_,_,locals)::_ => locals.get(StackVar(name))
        case Nil => None
      }
    case IntConst(v) =>
      Some(IntVal(v))
    case StringConst(v) =>
      Some(StringVal(v))
    case l =>
      println(l)
      ???
  }
  // TODO: rename getOrDefineClass
  def defineAs(l : RVal, pureExpr: PureExpr): State = l match{
    case LocalWrapper(localName, localType) =>
      val cshead: CallStackFrame = callStack.headOption.getOrElse(???) //TODO: add new stack frame if empty?
      val csheadNew = cshead.copy(locals = cshead.locals + (StackVar(localName)->pureExpr))
      val cstail = if (callStack.isEmpty) Nil else callStack.tail
      this.copy(callStack = csheadNew::cstail,
        pureFormula = pureFormula + PureConstraint(pureExpr, TypeComp, SubclassOf(localType)))
    case v =>
      println(v)
      ???
  }

  def getOrDefine(l : RVal): (PureVar,State) = l match{
    case LocalWrapper(name,localType) =>
      val cshead = callStack.headOption.getOrElse(???) //TODO: add new stack frame if empty?
      val cstail = if (callStack.isEmpty) Nil else callStack.tail
      cshead.locals.get(StackVar(name)) match {
        case Some(v@PureVar(_)) => (v, this)
        case None =>
          val newident = PureVar(nextAddr)
          (newident, State(
            callStack = cshead.copy(locals = cshead.locals + (StackVar(name) -> newident)) :: cstail,
            heapConstraints,
            pureFormula + PureConstraint(newident, TypeComp, SubclassOf(localType)),
            traceAbstraction,
            nextAddr + 1
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
    case (LocalWrapper(name,_), cshead::cstail) =>
      val newCsHead = cshead.removeStackVar(StackVar(name))
      this.copy(newCsHead::cstail)
    case _ =>
      ???
  }

  def clearPureVar(p:PureVar):State = {
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
    val pureVarFromConst = pureFormula.flatMap{
      case PureConstraint(p1,_,p2) => Set() ++ pureVarOpt(p1) ++ pureVarOpt(p2)
    }
    pureVarFromHeap ++ pureVarFromLocals ++ pureVarFromConst
  }
  def isNull(pv:PureVar):Boolean = {
    pureFormula.contains(PureConstraint(pv,Equals,NullVal))
  }
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
case object Equals extends CmpOp{
  override def toString:String = " == "
}
case object NotEquals extends CmpOp{
  override def toString:String = " != "
}
case object TypeComp extends CmpOp{
  override def toString:String = ""
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
sealed abstract class PureVal(v:Any) extends PureExpr with Val {
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
case class IntVal(v : Int) extends PureVal(v)
case class BoolVal(v : Boolean) extends PureVal(v)
case class StringVal(v : String) extends PureVal(v)
case object TopVal extends PureVal(null)
//TODO: do we need type constraints here?
sealed trait TypeConstraint extends PureVal
case class SubclassOf(clazz: String) extends TypeConstraint{
  override def toString:String = s"<: $clazz"
}
case class SuperclassOf(clazz:String) extends TypeConstraint {
  override def toString:String = s">: $clazz"
}
case class ClassType(typ:String) extends TypeConstraint {
  override def toString:String = s": $typ"
}

// pure var is a symbolic var (e.g. this^ from the paper)
sealed case class PureVar(id:Int) extends PureExpr with Val {
//  val id : Int = State.getId()
  override def getVars(s : Set[PureVar]) : Set[PureVar] = s + this

  override def substitute(toSub : PureExpr, subFor : PureVar) : PureExpr = if (subFor == this) toSub else this

  override def hashCode : Int = id*100271
  override def equals(other : Any) : Boolean = other match {
    case p : PureVar => this.id == p.id
    case _ => false
  }
  override def toString : String = "p-" + id
}