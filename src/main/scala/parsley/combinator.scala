package parsley

import parsley.Parsley.{LazyParsley, unit, empty, select, sequence, notFollowedBy}
import parsley.internal.deepembedding
import parsley.expr.chain
import parsley.registers.{get, gets, put, local}
import scala.annotation.{tailrec, implicitNotFound}

/** This module contains a huge number of pre-made combinators that are very useful for a variety of purposes. */
object combinator {
    /**`choice(ps)` tries to apply the parsers in the list `ps` in order, until one of them succeeds.
      *  Returns the value of the succeeding parser.*/
    def choice[A](ps: Parsley[A]*): Parsley[A] = ps.reduceLeftOption(_<|>_).getOrElse(empty)

    /**`attemptChoice(ps)` tries to apply the parsers in the list `ps` in order, until one of them succeeds.
      *  Returns the value of the succeeding parser. Utilises `<\>` vs choice's `<|>`.*/
    def attemptChoice[A](ps: Parsley[A]*): Parsley[A] = ps.reduceLeftOption(_<\>_).getOrElse(empty)

    /** `repeat(n, p)` parses `n` occurrences of `p`. If `n` is smaller or equal to zero, the parser is
      *  `pure(Nil)`. Returns a list of `n` values returned by `p`.*/
    def repeat[A](n: Int, p: =>Parsley[A]): Parsley[List[A]] = {
        lazy val _p = p
        sequence((for (_ <- 1 to n) yield _p): _*)
    }

    /**`option(p)` tries to apply parser `p`. If `p` fails without consuming input, it returns
      * `None`, otherwise it returns `Some` of the value returned by `p`.*/
    def option[A](p: =>Parsley[A]): Parsley[Option[A]] = p.map(Some(_)).getOrElse(None)

    /**`decide(p)` removes the option from inside parser `p`, and if it returned `None` will fail.*/
    def decide[A](p: =>Parsley[Option[A]]): Parsley[A] = p.collect {
        case Some(x) => x
    }

    /**`decide(p, q)` removes the option from inside parser `p`, if it returned `None` then `q` is executed.*/
    def decide[A](p: =>Parsley[Option[A]], q: =>Parsley[A]): Parsley[A] = select(p.map(_.toRight(())), q.map(x => (_: Unit) => x))

    /**optional(p) tries to apply parser `p`. It will parse `p` or nothing. It only fails if `p`
      * fails after consuming input. It discards the result of `p`.*/
    def optional(p: =>Parsley[_]): Parsley[Unit] = optionally(p, ())

    /**optionally(p, x) tries to apply parser `p`. It will always result in `x` regardless of
      * whether or not `p` succeeded or `p` failed without consuming input.*/
    def optionally[A](p: =>Parsley[_], x: =>A): Parsley[A] = {
        lazy val _x = x
        (p #> x).getOrElse(x)
    }

    /**`between(open, close, p)` parses `open`, followed by `p` and `close`. Returns the value returned by `p`.*/
    def between[A](open: =>Parsley[_],
                   close: =>Parsley[_],
                   p: =>Parsley[A]): Parsley[A] = open *> p <* close

    /** `many(p)` executes the parser `p` zero or more times. Returns a list of the returned values of `p`. */
    def many[A](p: =>Parsley[A]): Parsley[List[A]] = new Parsley(new deepembedding.Many(p.internal))

    /**`some(p)` applies the parser `p` *one* or more times. Returns a list of the returned values of `p`.*/
    def some[A](p: =>Parsley[A]): Parsley[List[A]] = manyN(1, p)

    /**`manyN(n, p)` applies the parser `p` *n* or more times. Returns a list of the returned values of `p`.*/
    def manyN[A](n: Int, p: =>Parsley[A]): Parsley[List[A]] = {
        lazy val _p = p
        @tailrec def go(n: Int, acc: Parsley[List[A]] = many(_p)): Parsley[List[A]] = {
            if (n == 0) acc
            else go(n-1, _p <::> acc)
        }
        go(n)
    }

    /** `skipMany(p)` executes the parser `p` zero or more times and ignores the results. Returns `()` */
    def skipMany[A](p: =>Parsley[A]): Parsley[Unit] = new Parsley(new deepembedding.SkipMany(p.internal))

    /**`skipSome(p)` applies the parser `p` *one* or more times, skipping its result.*/
    def skipSome[A](p: => Parsley[A]): Parsley[Unit] = skipManyN(1, p)

    /**`skipManyN(n, p)` applies the parser `p` *n* or more times, skipping its result.*/
    def skipManyN[A](n: Int, p: =>Parsley[A]): Parsley[Unit] = {
        lazy val _p = p
        @tailrec def go(n: Int, acc: Parsley[Unit] = skipMany(_p)): Parsley[Unit] =
        {
            if (n == 0) acc
            else go(n-1, _p *> acc)
        }
        go(n)
    }

    /**`sepBy(p, sep)` parses *zero* or more occurrences of `p`, separated by `sep`. Returns a list
      * of values returned by `p`.*/
    def sepBy[A, B](p: =>Parsley[A], sep: =>Parsley[B]): Parsley[List[A]] = sepBy1(p, sep).getOrElse(Nil)

    /**`sepBy1(p, sep)` parses *one* or more occurrences of `p`, separated by `sep`. Returns a list
      *  of values returned by `p`.*/
    def sepBy1[A, B](p: =>Parsley[A], sep: =>Parsley[B]): Parsley[List[A]] = {
        lazy val _p = p
        _p <::> many(sep *> _p)
    }

    /**`sepEndBy(p, sep)` parses *zero* or more occurrences of `p`, separated and optionally ended
      * by `sep`. Returns a list of values returned by `p`.*/
    def sepEndBy[A, B](p: =>Parsley[A], sep: =>Parsley[B]): Parsley[List[A]] = sepEndBy1(p, sep).getOrElse(Nil)

    /**`sepEndBy1(p, sep)` parses *one* or more occurrences of `p`, separated and optionally ended
      * by `sep`. Returns a list of values returned by `p`.*/
    def sepEndBy1[A, B](p: =>Parsley[A], sep: =>Parsley[B]): Parsley[List[A]] = new Parsley(new deepembedding.SepEndBy1(p.internal, sep.internal))

    /**`endBy(p, sep)` parses *zero* or more occurrences of `p`, separated and ended by `sep`. Returns a list
      * of values returned by `p`.*/
    def endBy[A, B](p: =>Parsley[A], sep: =>Parsley[B]): Parsley[List[A]] = many(p <* sep)

    /**`endBy1(p, sep)` parses *one* or more occurrences of `p`, separated and ended by `sep`. Returns a list
      * of values returned by `p`.*/
    def endBy1[A, B](p: =>Parsley[A], sep: =>Parsley[B]): Parsley[List[A]] = some(p <* sep)

    /**This parser only succeeds at the end of the input. This is a primitive parser.*/
    val eof: Parsley[Unit] = new Parsley(new deepembedding.Eof)

    /**This parser only succeeds if there is still more input.*/
    val more: Parsley[Unit] = notFollowedBy(eof)

    /**`manyUntil(p, end)` applies parser `p` zero or more times until the parser `end` succeeds.
      * Returns a list of values returned by `p`. This parser can be used to scan comments.*/
    def manyUntil[A, B](p: =>Parsley[A], end: =>Parsley[B]): Parsley[List[A]] = {
        new Parsley(new deepembedding.ManyUntil((end #> deepembedding.ManyUntil.Stop <|> p).internal))
    }

    /**`someUntil(p, end)` applies parser `p` one or more times until the parser `end` succeeds.
      * Returns a list of values returned by `p`.*/
    def someUntil[A, B](p: =>Parsley[A], end: =>Parsley[B]): Parsley[List[A]] = {
        lazy val _p = p
        lazy val _end = end
        notFollowedBy(_end) *> (_p <::> manyUntil(_p, _end))
    }

    /** `when(p, q)` will first perform `p`, and if the result is `true` will then execute `q` or else return unit.
      * @param p The first parser to parse
      * @param q If `p` returns `true` then this parser is executed
      * @return ()
      */
    def when(p: =>Parsley[Boolean], q: =>Parsley[Unit]): Parsley[Unit] = p ?: (q, unit)

    /** `whileP(p)` will continue to run `p` until it returns `false`. This is often useful in conjunction with stateful
      * parsers.
      * @param p The parser to continuously execute
      * @return ()
      */
    def whileP(p: =>Parsley[Boolean]): Parsley[Unit] = {
        lazy val whilePP: Parsley[Unit] = when(p, whilePP)
        whilePP
    }
}