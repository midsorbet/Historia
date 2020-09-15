package edu.colorado.hopper.solver

import java.lang.ClassValue

import edu.colorado.plv.bounder.symbolicexecutor.state.{ClassVal, CmpOp, Equals, NullVal, PureConstraint, PureExpr, PureVar, State}

trait Assumptions

class UnknownSMTResult(msg : String) extends Exception(msg)

/** SMT solver parameterized by its AST or expression type */
trait Solver[T] {
  // checking
  def checkSAT : Boolean
  def checkSATWithAssumptions(assumes : List[String]) : Boolean

  def getUNSATCore : String
  def push() : Unit
  def pop() : Unit

  // cleanup
  def dispose() : Unit
  // conversion from pure constraints to AST type of solver (T)
//  def mkAssert(p : PureConstraint) : Unit = mkAssert(toAST(p))
//  def mkAssertWithAssumption(assume : String, p : PureConstraint) : Unit = mkAssert(mkImplies(mkBoolVar(assume), toAST(p)))

  // comparison operations
  protected def mkEq(lhs : T, rhs : T) : T
  protected def mkNe(lhs : T, rhs : T) : T
  protected def mkGt(lhs : T, rhs : T) : T
  protected def mkLt(lhs : T, rhs : T) : T
  protected def mkGe(lhs : T, rhs : T) : T
  protected def mkLe(lhs : T, rhs : T) : T

  // logical and arithmetic operations
  protected def mkNot(o : T) : T
  protected def mkImplies(lhs : T, rhs : T) : T

  protected def mkAdd(lhs : T, rhs : T) : T
  protected def mkSub(lhs : T, rhs : T) : T
  protected def mkMul(lhs : T, rhs : T) : T
  protected def mkDiv(lhs : T, rhs : T) : T
  protected def mkRem(lhs : T, rhs : T) : T
  protected def mkAnd(lhs : T, rhs : T) : T
  protected def mkOr(lhs : T, rhs : T) : T
  protected def mkXor(lhs : T, rhs : T) : T

  // creation of variables, constants, assertions
  protected def mkIntVal(i : Int) : T
  protected def mkBoolVal(b : Boolean) : T
  protected def mkIntVar(s : String) : T
  protected def mkBoolVar(s : String) : T
  protected def mkObjVar(s:String) : T
  protected def mkAssert(t : T) : Unit
  protected def solverSimplify(t: T): Option[T]

  def toAST(p : PureConstraint) : T = p match {
    case PureConstraint(lhs, op, rhs) => toAST(toAST(lhs), op, toAST(rhs))
    case _ => ???
//    case PureDisjunctiveConstraint(terms) => terms.foldLeft (None : Option[T]) ((combined, term) => combined match {
//      case Some(combined) => Some(mkOr(toAST(term), combined))
//      case None => Some(toAST(term))
//    }).get
  }
  //TODO: can we use z3 to solve cha?
  def toAST(p : PureExpr) : T = p match {
    case p:PureVar => mkObjVar(p.id.toString)
    case NullVal => mkBoolVal(false)
    case ClassVal(t) => mkBoolVal(true)
    case _ =>
      ???
  }
  def toAST(lhs : T, op : CmpOp, rhs : T) : T = op match {
    case Equals => mkEq(lhs,rhs)
    case _ =>
      ???
  }
  def simplify(state:State):Option[State] = state match{
    case State(h::t, heap, pure) => {
      def ast =  pure.foldLeft(mkBoolVal(true))((acc,v) => mkAnd(acc, toAST(v)))
      val simpleAst = solverSimplify(ast)
      simpleAst.map(_ => state) //TODO: actually simplify?
    }
    case State(Nil,_,_) =>
      ???
  }
}