package parsley

import parsley.Parsley._

import scala.annotation.tailrec
import scala.collection.mutable.Buffer
import language.existentials

// TODO Investigate effect of :+= instead of :+ for the buffers
// TODO Perform final optimisation stage on end result, likely to perform some extra optimisation, but possibly less
class Parsley[+A](
    // The instructions that shall be executed by this parser
    val instrs: Buffer[Instruction],
    // The subroutines currently collected by the compilation
    val subs: Map[String, Buffer[Instruction]])
{
    def flatMap[B](f: A => Parsley[B]): Parsley[B] = instrs.last match
    {
        // return x >>= f == f x
        case Push(x: A @unchecked) => new Parsley(instrs.init ++ f(x).instrs, subs)
        case _ => new Parsley(instrs :+ DynSub[A](x => f(x).instrs), subs)
    }
    def map[B](f: A => B): Parsley[B] = instrs.last match
    {
        // Pure application can be resolved at compile-time
        case Push(x: A @unchecked) => new Parsley(instrs.init :+ Push(f(x)), subs)
        // p.map(f).map(g) = p.map(g . f) (functor law)
        case Perform(g) => new Parsley(instrs.init :+ Perform(g.asInstanceOf[Function[C forSome {type C}, A]].andThen(f)), subs)
        case _ => new Parsley(instrs :+ Perform(f), subs)
    }
    @inline def <#>[B](f: =>A => B): Parsley[B] = map(f)
    @inline def <#>:[B](f: =>A => B): Parsley[B] = map(f)
    @inline def >>[B](p: Parsley[B]): Parsley[B] = this *> p
    def *>[B](p: Parsley[B]): Parsley[B] = instrs.last match
    {
        // pure x *> p == p (consequence of applicative and functor laws)
        case Push(_) => new Parsley(instrs.init ++ p.instrs, subs ++ p.subs)
        case _ => new Parsley((instrs :+ Pop) ++ p.instrs, subs ++ p.subs)
    }
    def <*[B](p: Parsley[B]): Parsley[A] = p.instrs.last match
    {
        // p <* pure x == p (consequence of applicative and functor laws)
        case Push(_) => new Parsley(instrs ++ p.instrs.init, subs ++ p.subs)
        case _ => new Parsley(instrs ++ p.instrs :+ Pop, subs ++ p.subs)
    }
    def #>[B](x: B): Parsley[B] = instrs.last match
    {
        // pure x #> y == pure y (consequence of applicative and functor laws)
        case Push(_) => new Parsley(instrs.init :+ Push(x), subs)
        case _ => new Parsley(instrs :+ Pop :+ Push(x), subs)
    }
    def <*>:[B](p: Parsley[A => B]): Parsley[B] = p.instrs.last match
    {
        // pure(f) <*> p == f <#> p (consequence of applicative laws)
        case Push(f) => instrs.last match
        {
            // f <#> pure x == pure (f x) (applicative law)
            case Push(x: A @unchecked) => new Parsley(p.instrs.init ++ instrs.init :+ Push(f.asInstanceOf[Function[A, B]](x)), p.subs ++ subs)
            // p.map(f).map(g) == p.map(g . f) (functor law)
            case Perform(g) =>
                new Parsley(p.instrs.init ++ instrs.init :+
                            Perform(f.asInstanceOf[Function[A, B]].compose(g.asInstanceOf[Function[C forSome {type C}, A]])), p.subs ++ subs)
            case _ => new Parsley(p.instrs.init ++ instrs :+ Perform[A, B](f.asInstanceOf[Function[A, B]]), p.subs ++ subs)
        }
        case Perform(f: Function[A, Any=>Any] @unchecked) => instrs.last match
        {
            // fusion law: (f <$> x) <*> pure y == (($y) . f) <$> x
            case Push(y) => new Parsley(p.instrs.init ++ instrs.init :+ Perform[A, Any](x => f(x)(y)), p.subs ++ subs)
            case _ => new Parsley(p.instrs ++ instrs :+ Apply, p.subs ++ subs)
        }
        case _ => instrs.last match
        {
            // interchange law: u <*> pure y = ($y) <$> u
            case Push(x: A @unchecked) => new Parsley(p.instrs ++ instrs.init :+ Perform[A => B, B](f => f(x)), p.subs ++ subs)
            case _ => new Parsley(p.instrs ++ instrs :+ Apply, p.subs ++ subs)
        }
    }
    def <*>[B, C](p: Parsley[B])(implicit ev: A => (B => C)): Parsley[C] = instrs.last match
    {
        // pure(f) <*> p == f <#> p (consequence of applicative laws)
        case Push(f) => p.instrs.last match
        {
            // f <#> pure x == pure (f x) (applicative law)
            case Push(x: B @unchecked) => new Parsley(instrs.init ++ p.instrs.init :+ Push(f.asInstanceOf[Function[B, C]](x)), subs ++ p.subs)
            // p.map(f).map(g) == p.map(g . f) (functor law)
            case Perform(g) =>
                new Parsley(instrs.init ++ p.instrs.init :+
                    Perform(f.asInstanceOf[Function[B, C]].compose(g.asInstanceOf[Function[A forSome {type A}, B]])), subs ++ p.subs)
            case _ => new Parsley(instrs.init ++ p.instrs :+ Perform[B, C](f.asInstanceOf[Function[B, C]]), subs ++ p.subs)
        }
        case Perform(f: Function[B, Any=>Any] @unchecked) => p.instrs.last match
        {
            // fusion law: (f <$> x) <*> pure y == (($y) . f) <$> x
            case Push(y) => new Parsley(instrs.init ++ p.instrs.init :+ Perform[B, Any](x => f(x)(y)), subs ++ p.subs)
            case _ => new Parsley(instrs ++ p.instrs :+ Apply, subs ++ p.subs)
        }
        case _ => p.instrs match
        {
            // interchange law: u <*> pure y == ($y) <$> u
            case Buffer(Push(x: B @unchecked)) => new Parsley(instrs :+ Perform[B => C, C](f => f(x)), subs ++ p.subs)
            case _ => new Parsley(instrs ++ p.instrs :+ Apply, subs ++ p.subs)
        }
    }
    @inline def <**>[B](f: Parsley[A => B]): Parsley[B] = lift2[A, A=>B, B]((x, f) => f(x), this, f)
    @inline def <::>[A_ >: A](ps: Parsley[List[A_]]): Parsley[List[A_]] = lift2[A, List[A_], List[A_]](_::_, this, ps)
    def <|>[A_ >: A](q: Parsley[A_]): Parsley[A_] = instrs match
    {
        // pure results always succeed
        case Buffer(Push(_)) => new Parsley[A_](instrs, subs ++ q.subs)
        // empty <|> q == q (alternative law)
        case Buffer(Fail(_)) => q
        case _ => q.instrs match
        {
            // p <|> empty = p (alternative law)
            case Buffer(Fail(_)) => this
            // p <|> p == p (this needs refinement to be label invariant, we want structure
            case instrs_ if instrs == instrs_ => this
            // I imagine there is space for optimisation of common postfix and prefixes in choice
            // this would allow for further optimisations with surrounding integration
            // does it imply that there is a try scope wrapping p?
            // NOTE: Prefix optimisation appears to be correct i.e.
            //      (x *> y) <|> (x *> z) === x *> (y <|> z) without need of try
            // NOTE: Postfix optimisation is also correct
            //      (y *> x) <|> (z *> x) == (y <|> z) *> x, noting that y and z are necessarily impure but this always holds
            case _ =>
                val goodLabel = nextLabel+1
                val handler = nextLabel+2
                nextLabel += 2
                new Parsley[A_]((InputCheck(handler) +: instrs :+ Label(handler) :+ JumpGood(goodLabel)) ++ q.instrs :+ Label(goodLabel), subs ++ q.subs)
        }
    }
    @inline def </>[A_ >: A](x: A_): Parsley[A_] = this <|> pure(x)
    @inline def <\>[A_ >: A](q: Parsley[A_]): Parsley[A_] = tryParse(this) <|> q
    @inline def <|?>[B](p: Parsley[B], q: Parsley[B])(implicit ev: Parsley[A] => Parsley[Boolean]): Parsley[B] = choose(this, p, q)
    @inline def >?>(pred: A => Boolean, msg: String): Parsley[A] = guard(this, pred, msg)
    @inline def >?>(pred: A => Boolean, msggen: A => String) = guard(this, pred, msggen)
    override def toString: String = s"(${instrs.toString}, ${subs.toString})"
}

object Parsley
{
    def pure[A](a: A): Parsley[A] = new Parsley[A](Buffer(Push(a)), Map.empty)
    def fail[A](msg: String): Parsley[A] = new Parsley[A](Buffer(Fail(msg)), Map.empty)
    def fail[A](msggen: Parsley[A], finaliser: A => String): Parsley[A] =  new Parsley[A](msggen.instrs :+ FastFail(finaliser), msggen.subs)
    def empty[A]: Parsley[A] = fail("unknown error")
    def tryParse[A](p: Parsley[A]): Parsley[A] =
    {
        nextLabel += 1
        new Parsley(TryBegin(nextLabel) +: p.instrs :+ Label(nextLabel) :+ TryEnd, p.subs)
    }
    @inline def lift2[A, B, C](f: (A, B) => C, p: Parsley[A], q: Parsley[B]): Parsley[C] = p.map(x => (y: B) => f(x, y)) <*> q
    //def lift2[A, B, C](f: (A, B) => C, p: Parsley[A], q: Parsley[B]): Parsley[C] = 
    //{
    //    new Parsley(p.instrs ++ q.instrs :+ Lift(f), p.subs ++ q.subs)
    //}
    def char(c: Char): Parsley[Char] = new Parsley(Buffer(CharTok(c)), Map.empty)
    def satisfy(f: Char => Boolean): Parsley[Char] = new Parsley(Buffer(Satisfies(f)), Map.empty)
    def string(s: String): Parsley[String] = new Parsley(Buffer(StringTok(s)), Map.empty)
    @inline
    def choose[A](b: Parsley[Boolean], p: Parsley[A], q: Parsley[A]): Parsley[A] =
    {
        b.flatMap(b => if (b) p else q)
    }
    @inline
    def guard[A](p: Parsley[A], pred: A => Boolean, msg: String): Parsley[A] =
    {
        p.flatMap(x => if (pred(x)) pure(x) else fail(msg))
    }
    @inline
    def guard[A](p: Parsley[A], pred: A => Boolean, msggen: A => String): Parsley[A] =
    {
        p.flatMap(x => if (pred(x)) pure(x) else fail(pure(x), msggen))
    }

    var knotScope: Set[String] = Set.empty
    var nextLabel: Int = -1
    def reset(): Unit =
    {
        knotScope = Set.empty
        nextLabel = -1
    }
    def knot[A](name: String, p_ : =>Parsley[A]): Parsley[A] =
    {
        // TODO: We need to consider what happens with labels, really they need to be set to 0 for this
        // I.e. This should behave more like a reader monad, not state... Difficult to achieve though
        lazy val p = p_
        if (knotScope.contains(name)) new Parsley(Buffer(Call(name)), Map.empty)
        else
        {
            knotScope += name
            // FIXME: This doesn't work properly due to inlining... We need to work out a better way around this...
            // This should be fine, p is a lazy value, so it would have been forced until next lines
            val curLabel = nextLabel
            nextLabel = -1
            // Perform inline expansion optimisation, reduce to minimum knot-tie
            val instrs = p.instrs.flatMap
            {
                // Inlining is disabled whilst current label management is in force
                //case Call(name_) if name != name_ && p.subs.contains(name_) => p.subs(name_)
                case instr => Vector(instr)
            }
            nextLabel = curLabel
            new Parsley(Buffer(Call(name)), p.subs + (name -> instrs))
        }
    }

    implicit class Knot[A](name: String)
    {
        def <%>(p: =>Parsley[A]): Parsley[A] = knot(name, p)
    }
    
    implicit class Mapper[A, B](f: A => B)
    {
        def <#>(p: Parsley[A]): Parsley[B] = p.map(f)
    }

    def optimise[A](p: Parsley[A]): Parsley[A] =
    {
        val instrs = p.instrs
        val subs = p.subs
        /*
        Removing Labels;
            Ideally we want to remove labels in a single pass in O(n).
            However, don't know the index because we don't know how many
            further labels there are behind the invokation point.
            Potentially we do however, it might be the label's number itself?
            If we can make that so it is indeed the case then the rest of this
            work becomes trivial...

            I believe this to *currently* be the case;
                <|> is the only combinator that injects labels, but consider
                p <|> q, then p and q are compiled in that order then the label
                is injected, hence P; Q; LABEL. Even if P and Q themselves
                contained labels, they would claim a lower number, with p taking
                precedences.
         */
        @tailrec
        // This might be very slow, it might be best to convert to vectors before we remove each element?
        def process(instrs: Buffer[Instruction],
                    labels: Map[Int, Int] = Map.empty,
                    processed: Buffer[Instruction] = Buffer.empty): Buffer[Instruction] = instrs match
        {
            case instrs :+ Label(x) =>
                val idx = instrs.size - x
                process(instrs, labels + (x -> idx), processed)
            case instrs :+ JumpGood(x) => process(instrs, labels, processed :+ JumpGood(labels(x)))
            case instrs :+ InputCheck(handler) => process(instrs, labels, processed :+ InputCheck(labels(handler)))
            case instrs :+ TryBegin(handler) => process(instrs, labels, processed :+ TryBegin(labels(handler)))
            case instrs :+ Pop :+ Push(x) => process(instrs, labels, processed :+ Exchange(x))
            case instrs :+ instr => process(instrs, labels, processed :+ instr)
            case Buffer() => processed.reverse
        }
        new Parsley(process(instrs), subs.mapValues(process(_)))
    }

    def inf: Parsley[Int] = "inf" <%> inf.map[Int](_+1).map[Int](_+2)
    def expr: Parsley[Int] = "expr" <%> (pure[Int=>Int=>Int](x => y => x + y) <*> pure[Int](10) <*> expr)
    def monad: Parsley[Int] = for (x <- pure[Int](10); y <- pure[Int](20)) yield x + y
    def foo: Parsley[Int] = "foo" <%> (bar <* pure(20))
    def bar: Parsley[Int] = "bar" <%> (foo *> pure(10))
    def many[A](p: Parsley[A]): Parsley[List[A]] = s"many(${p.instrs})" <%> (some(p) </> Nil)
    def sepEndBy1[A, B](p: Parsley[A], sep: Parsley[B]): Parsley[List[A]] = s"sepEndBy1" <%> (p <::> ((sep >> sepEndBy(p, sep)) </> Nil))
    def sepEndBy[A, B](p: Parsley[A], sep: Parsley[B]): Parsley[List[A]] = s"sepEndBy" <%> (sepEndBy1(p, sep) </> Nil)
    def some[A](p: Parsley[A]): Parsley[List[A]] = s"some(${p.instrs})" <%> (p <::> many(p))
    def repeat[A](n: Int, p: Parsley[A]): Parsley[List[A]] =
        if (n > 0) p <::> repeat(n-1, p)
        else pure(Nil)

    def main(args: Array[String]): Unit =
    {
        println(pure[Int=>Int=>Int](x => y => x + y) <*> pure(10) <*> pure(20))
        reset()
        println(inf)
        reset()
        println(expr)
        reset()
        println(monad)
        reset()
        println(foo)
        reset()
        println(optimise(many(pure[Int](10))))
        reset()
        println(optimise(sepEndBy('x', 'a')))
        reset()
        println(repeat(10, pure[Unit](())))
        reset()
        println(((x: Int) => x * 2) <#> (((x: Char) => x.toInt) <#> '0'))
        reset()
    }
}