package parsley.token

import parsley.Parsley
import scala.language.higherKinds

/**
  * The Impl trait is used to provide implementation of the parser requirements from `LanguageDef`
  */
sealed trait Impl
/**
  * The implementation provided is a parser which parses the required token.
  * @param p The parser which will parse the token
  */

final case class Parser(p: Parsley[_]) extends Impl
/**
  * The implementation provided is a function which matches on the input streams characters
  * @param f The predicate that input tokens are tested against
  */

final case class Predicate(f: Char => Boolean) extends Impl

/**
  * This implementation states that the required functionality is not required. If it is used it will raise an error
  * at parse-time
  */
case object NotRequired extends Impl

private [parsley] final case class BitSetImpl(cs: TokenSet) extends Impl

/**
  * This implementation uses a set of valid tokens. It is converted to a high-performance BitSet.
  */
object CharSet
{
    /**
      * @param cs The set to convert
      */
    def apply(cs: Set[Char]): Impl = BitSetImpl(new BitSet(Left(cs)))
    def apply(cs: Char*): Impl = apply(Set(cs: _*))
}

/**
  * This implementation uses a predicate to generate a BitSet. This should be preferred over `Predicate` when the
  * function in question is expensive to execute and the parser itself is expected to be used many times. If the
  * predicate is cheap, this is unlikely to provide any performance improvements, but will instead incur heavy space
  * costs
  */
object BitGen
{
    def apply(f: Char => Boolean): Impl = BitSetImpl(new BitSet(Right(f)))
}