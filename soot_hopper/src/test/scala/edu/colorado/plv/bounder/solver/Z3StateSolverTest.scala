package edu.colorado.plv.bounder.solver

import com.microsoft.z3.{ArithExpr, BoolExpr, Context, Expr, IntExpr, Solver, Symbol}
import edu.colorado.plv.bounder.ir.{CBEnter, CallbackMethodInvoke}
import edu.colorado.plv.bounder.lifestate.LifeState.{And, I, LSAbsBind, NI, Not, Or}
import edu.colorado.plv.bounder.symbolicexecutor.state.{AbsAnd, AbsArrow, AbsEq, AbsFormula, CallStackFrame, Equals, FieldPtEdge, NotEquals, NullVal, PureConstraint, PureVar, StackVar, State, SubclassOf, TraceAbstraction, TypeComp}
import edu.colorado.plv.bounder.testutils.TestIRMethodLoc

class Z3StateSolverTest extends org.scalatest.FunSuite {
  val dummyLoc = CallbackMethodInvoke(fmwClazz = "",
    fmwName="void foo()", TestIRMethodLoc("foo"))
  val v = PureVar()
  val frame = CallStackFrame(dummyLoc, None, Map(StackVar("x") -> v))
  test("null not null") {
    val ctx = new Context
    val solver: Solver = ctx.mkSolver()
    val hierarchy : Map[String, Set[String]] =
      Map("Object" -> Set("String", "Foo", "Bar", "Object"),
        "String" -> Set("String"), "Foo" -> Set("Bar", "Foo"), "Bar" -> Set("Bar"))
    val pc = new PersistantConstraints(ctx, solver, hierarchy)
    val statesolver = new Z3StateSolver(pc)

    val v2 = PureVar()
    val constraints = Set(PureConstraint(v2, NotEquals, NullVal), PureConstraint(v2, Equals, NullVal))
    val refutableState = State(List(frame),
      Map(FieldPtEdge(v,"f") -> v2),constraints,Set())
    val simplifyResult = statesolver.simplify(refutableState)
    assert(!simplifyResult.isDefined)
  }
  test("alias") {
    val ctx = new Context
    val solver: Solver = ctx.mkSolver()
    val hierarchy : Map[String, Set[String]] =
      Map("Object" -> Set("String", "Foo", "Bar", "Object"),
        "String" -> Set("String"), "Foo" -> Set("Bar", "Foo"), "Bar" -> Set("Bar"))
    val pc = new PersistantConstraints(ctx, solver, hierarchy)
    val statesolver = new Z3StateSolver(pc)

    // aliased and contradictory nullness
    val v2 = PureVar()
    val v3 = PureVar()
    val constraints = Set(PureConstraint(v2, NotEquals, NullVal),
      PureConstraint(v3, Equals, NullVal))
    val refutableState = State(List(frame),
      Map(FieldPtEdge(v,"f") -> v2),constraints + PureConstraint(v2, Equals, v3),Set())
    val simplifyResult = statesolver.simplify(refutableState)
    assert(!simplifyResult.isDefined)
  }
  test("aliased object implies fields must be aliased") {
    val ctx = new Context
    val solver: Solver = ctx.mkSolver()
    val hierarchy : Map[String, Set[String]] =
      Map("Object" -> Set("String", "Foo", "Bar", "Object"),
        "String" -> Set("String"), "Foo" -> Set("Bar", "Foo"), "Bar" -> Set("Bar"))
    val pc = new PersistantConstraints(ctx, solver, hierarchy)
    val statesolver = new Z3StateSolver(pc)

    // aliased but field is not aliased should be infeasible
    val v2 = PureVar()
    val v3 = PureVar()
    val v4 = PureVar()
    val constraints = Set(
      PureConstraint(v, Equals, v2))
    val refutableState = State(List(frame),
      Map(FieldPtEdge(v,"f") -> v3, FieldPtEdge(v2,"f") -> v4),constraints + PureConstraint(v3, NotEquals, v4), Set())
    val simplifyResult = statesolver.simplify(refutableState)
    assert(!simplifyResult.isDefined)

    // aliased with field aliased should be feasilbe
    val unrefutableState = State(List(frame),
      Map(FieldPtEdge(v,"f") -> v3, FieldPtEdge(v2,"f") -> v4),constraints + PureConstraint(v3, Equals, v4), Set())
    val simplifyResult2 = statesolver.simplify(unrefutableState)
    assert(simplifyResult2.isDefined)
  }
  test("aliased object implies fields must be aliased refuted by type constraints") {
    val ctx = new Context
    val solver: Solver = ctx.mkSolver()
    val hierarchy : Map[String, Set[String]] =
      Map("Object" -> Set("String", "Foo", "Bar", "Object"),
        "String" -> Set("String"), "Foo" -> Set("Bar", "Foo"), "Bar" -> Set("Bar"))
    val pc = new PersistantConstraints(ctx, solver, hierarchy)
    val statesolver = new Z3StateSolver(pc)

    // aliased and contradictory types of field
    val v2 = PureVar()
    val v3 = PureVar()
    val v4 = PureVar()
    val constraints = Set(
      PureConstraint(v, Equals, v2),
      PureConstraint(v3, TypeComp, SubclassOf("String")),
      PureConstraint(v4, TypeComp, SubclassOf("Foo"))
    )
    val refutableState = State(List(frame),
      Map(FieldPtEdge(v,"f") -> v3, FieldPtEdge(v2,"f") -> v4),constraints, Set())
    val simplifyResult = statesolver.simplify(refutableState)
    assert(!simplifyResult.isDefined)

    // aliased and consistent field type constraints
    val constraints2 = Set(
      PureConstraint(v, Equals, v2),
      PureConstraint(v3, TypeComp, SubclassOf("String")),
      PureConstraint(v4, TypeComp, SubclassOf("Object"))
    )
    val state = State(List(frame),
      Map(FieldPtEdge(v,"f") -> v3, FieldPtEdge(v2,"f") -> v4),constraints2, Set())
    val simplifyResult2 = statesolver.simplify(state)
    assert(simplifyResult2.isDefined)
  }
  test("Trace abstraction solving") {
    val ctx = new Context
    val solver: Solver = ctx.mkSolver()
    val hierarchy : Map[String, Set[String]] =
      Map("Object" -> Set("String", "Foo", "Bar", "Object"),
        "String" -> Set("String"), "Foo" -> Set("Bar", "Foo"), "Bar" -> Set("Bar"))
    val pc = new PersistantConstraints(ctx, solver, hierarchy)
    val statesolver = new Z3StateSolver(pc)

    // Lifestate atoms for next few tests
    val i = I(CBEnter, Set(("foo", "bar")), "a" :: Nil)
    val i2 = I(CBEnter, Set(("foo", "baz")), "a" :: Nil)
    val i3 = I(CBEnter, Set(("foo", "baz")), "b" :: Nil)
    // NI(a.bar(), a.baz())
    val niBarBaz = NI(i,i2)

    // pure vars for next few tests
    val p1 = PureVar()
    val p2 = PureVar()

    val abs1: TraceAbstraction = AbsArrow(
      AbsAnd(AbsAnd(AbsFormula(niBarBaz), AbsEq("a",p1)), AbsEq("b",p1)),
      i3
    )
    val state1 = State(Nil, Map(),Set(), Set(abs1))
    val res1 = statesolver.simplify(state1)
    assert(!res1.isDefined)

    //TODO: more tests
    // [NI(m1^,m2^) OR (NOT NI(m1^,m2^)) ] AND (a |->a^) => TRUE
    val pred2 = Or(NI(i,i2),Not(NI(i,i2)))
    //val state2 = State(Nil, Map(),Set(), Set(LSAbstraction(pred2, Map("a"-> p1))))
    //val res2 = statesolver.simplify(state2)
    //assert(res2.isDefined)
  }
  test("quantifier example") {
    val ctx = new Context
    val solver: Solver = ctx.mkSolver()
    val foo1:ArithExpr = ctx.mkConst("foo", ctx.mkIntSort()).asInstanceOf[ArithExpr]
    val f = ctx.mkFuncDecl("f",ctx.mkIntSort(), ctx.mkBoolSort())
    val expr:Expr = ctx.mkIff(
      f.apply(foo1).asInstanceOf[BoolExpr],
      ctx.mkGt(foo1, ctx.mkInt(0)))
    val a = ctx.mkForall(Array(foo1),expr, 1, null,null,
      ctx.mkSymbol("a"), ctx.mkSymbol("b"))

    solver.add(a)
    solver.check()
    val m = solver.getModel

    println(m)
  }

}
