package scala.annotation

/** Annotations to control the behavior of the compiler check for safe initialization of static obects.
 *
 *  Programmers usually do not need to use any annotations. They are intended for complex initialization
 *  code in static objects.
 */
object init:

  /** Widen the abstract value of the argument so that its height is below the specified height.
   *
   *  It can be used to mark method or constructor arguments, as the following example shows:
   *
   *     class A(x: Int):
   *       def squre(): Int = x*x
   *
   *     object B:
   *       val a = build(new A(10): @init.expose)   // <-- usage
   *
   *       def build(o: A) = new A(o.square())        // calling methods on parameter
   *
   *  By default, method and constructor arguments are widened to height 1.
   */
  final class widen(height: Int) extends StaticAnnotation

  /** Introduce a region context.
   *
   *  The same mutable field in the same region have the same abstract representation.
   *
   *  The concept of regions is intended to make context-sensitivity tunable for complex use cases.
   *
   *  Example:
   *
   *      trait B { def foo(): Int }
   *      class C(var x: Int) extends B { def foo(): Int = 20 }
   *      class D(var y: Int) extends B { def foo(): Int = A.m }
   *      class Box(var value: B)
   *
   *       object A:
   *         val box1: Box = region { new Box(new C(5))  }
   *         val box2: Box = region { new Box(new D(10)) }
   *         val m: Int = box1.value.foo()
   *
   *  In the above, without the two region annotations, the two objects `box1` and `box2` are in the same region.
   *  Therefore, the field `box1.value` and `box2.value` points to both instances of `C` and `D`. Consequently,
   *  the method call `box1.value.foo()` will be invalid, because it reaches `A.m`, which is not yet initialized.
   *  The explicit context annotation solves the problem.
   */
  def region[T](v: T): T = v
