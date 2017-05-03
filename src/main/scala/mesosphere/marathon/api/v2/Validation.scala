package mesosphere.marathon
package api.v2

import java.net._

import com.wix.accord.Descriptions._
import com.wix.accord._
import com.wix.accord.dsl._
import com.wix.accord.ViolationBuilder._
import mesosphere.marathon.state.FetchUri
import mesosphere.marathon.stream.Implicits._
import org.slf4j.LoggerFactory

import scala.collection.GenTraversableOnce
import scala.util.matching.Regex
import scala.language.implicitConversions

// TODO(jdef) move this into package "validation"
trait Validation {
  def validateOrThrow[T](t: T)(implicit validator: Validator[T]): T = validate(t) match {
    case Success => t
    case f: Failure => throw ValidationFailedException(t, f)
  }

  implicit def optional[T](implicit validator: Validator[T]): Validator[Option[T]] = {
    new Validator[Option[T]] {
      override def apply(option: Option[T]): Result = option.map(validator).getOrElse(Success)
    }
  }

  /**
    * when used in a `validator` should be wrapped with `valid(...)`
    */
  def definedAnd[T](implicit validator: Validator[T]): Validator[Option[T]] = {
    new Validator[Option[T]] {
      override def apply(option: Option[T]): Result = option.map(validator).getOrElse(
        Failure(Set(RuleViolation(None, "not defined")))
      )
    }
  }

  /**
    * When the supplied expression `b` yields `true` then the supplied (implicit) validator is returned; otherwise
    * `Success`. Similar to [[conditional]] except the predicate producing the boolean is not parameterized.
    */
  def implied[T](b: => Boolean)(implicit validator: Validator[T]): Validator[T] = new Validator[T] {
    override def apply(t: T): Result = if (!b) Success else validator(t)
  }

  def conditional[T](b: T => Boolean)(implicit validator: Validator[T]): Validator[T] = new Validator[T] {
    override def apply(t: T): Result = if (!b(t)) Success else validator(t)
  }

  implicit def every[T](implicit validator: Validator[T]): Validator[Iterable[T]] = {
    // TODO: as long as accord does not support all description combinations, a custom combine is needed.
    // See: https://github.com/wix/accord/issues/109
    def combineDesc(left: Description, right: Description): Description = (left, right) match {
      case (lhs: Explicit, Indexed(index, Empty)) => AccessChain(Indexed(index, SelfReference), lhs)
      case (lhs: Generic, Indexed(index, Empty)) => AccessChain(Indexed(index, SelfReference), lhs)
      case (lhs, rhs) => combine(lhs, rhs)
    }
    def mapViolation(violation: Violation, desc: Description): Violation = {
      violation match {
        case RuleViolation(value, constraint, description) => RuleViolation(value, constraint, combineDesc(description, desc))
        case GroupViolation(value, constraint, children, description) => GroupViolation(value, constraint, children, combineDesc(description, desc))
      }
    }
    new Validator[Iterable[T]] {
      override def apply(seq: Iterable[T]): Result = {
        seq.zipWithIndex.foldLeft[Result](Success) {
          case (result, (item, index)) =>
            validator(item) match {
              case Success => result
              case Failure(violations) => result.and(Failure(violations.map(mapViolation(_, Indexed(index.toLong)))))
            }
        }
      }
    }
  }

  def featureEnabled[T](enabledFeatures: Set[String], feature: String): Validator[T] = {
    isTrue(s"Feature $feature is not enabled. Enable with --enable_features $feature)") { _ =>
      enabledFeatures.contains(feature)
    }
  }

  /**
    * Yield a validator for `T` only if the supplied `feature` is present in the set of `enabledFeatures`; otherwise
    * `Success`.
    */
  def featureEnabledImplies[T](enabledFeatures: Set[String], feature: String)(implicit v: Validator[T]): Validator[T] =
    implied[T](enabledFeatures.contains(feature))(v)

  def urlIsValid: Validator[String] = {
    new Validator[String] {
      def apply(url: String) = {
        try {
          new URL(url)
          Success
        } catch {
          case e: MalformedURLException => Failure(Set(RuleViolation(url, e.getMessage)))
        }
      }
    }
  }

  def uriIsValid: Validator[String] = {
    new Validator[String] {
      def apply(uri: String) = {
        try {
          new URI(uri)
          Success
        } catch {
          case _: URISyntaxException => Failure(Set(RuleViolation(uri, "URI has invalid syntax.")))
        }
      }
    }
  }

  def fetchUriIsValid: Validator[FetchUri] = validator[FetchUri] { fetch =>
    fetch.uri is uriIsValid
  }

  def elementsAreUnique[A](errorMessage: String = "Elements must be unique."): Validator[Seq[A]] = {
    new Validator[Seq[A]] {
      def apply(seq: Seq[A]) = areUnique(seq, errorMessage)
    }
  }

  def elementsAreUniqueBy[A, B](
    fn: A => B,
    errorMessage: String = "Elements must be unique.",
    filter: B => Boolean = { _: B => true }): Validator[Iterable[A]] = {
    new Validator[Iterable[A]] {
      def apply(seq: Iterable[A]) = areUnique(seq.map(fn).filterAs(filter)(collection.breakOut), errorMessage)
    }
  }

  def elementsAreUniqueByOptional[A, B](
    fn: A => GenTraversableOnce[B],
    errorMessage: String = "Elements must be unique.",
    filter: B => Boolean = { _: B => true }): Validator[Iterable[A]] = {
    new Validator[Iterable[A]] {
      def apply(seq: Iterable[A]) = areUnique(seq.flatMap(fn).filterAs(filter)(collection.breakOut), errorMessage)
    }
  }

  def elementsAreUniqueWithFilter[A](
    fn: A => Boolean,
    errorMessage: String = "Elements must be unique."): Validator[Seq[A]] = {
    new Validator[Seq[A]] {
      def apply(seq: Seq[A]) = areUnique(seq.filter(fn), errorMessage)
    }
  }

  private[this] def areUnique[A](seq: Seq[A], errorMessage: String): Result = {
    if (seq.size == seq.distinct.size) Success
    else Failure(Set(RuleViolation(seq, errorMessage)))
  }

  def theOnlyDefinedOptionIn[A <: Product, B](product: A): Validator[Option[B]] =
    new Validator[Option[B]] {
      def apply(option: Option[B]) = {
        option match {
          case Some(prop) =>
            val n = product.productIterator.count {
              case Some(_) => true
              case _ => false
            }

            if (n == 1)
              Success
            else
              Failure(Set(RuleViolation(product, "not allowed in conjunction with other properties.")))
          case None => Success
        }
      }
    }

  def notOneOf[T <: AnyRef](options: T*): Validator[T] = {
    new NullSafeValidator[T](
      test = !options.contains(_),
      failure = _ -> s"can not be one of (${options.mkString(",")})"
    )
  }

  def oneOf[T <: AnyRef](options: Set[T]): Validator[T] = {
    new NullSafeValidator[T](
      test = options.contains,
      failure = _ -> s"is not one of (${options.mkString(",")})"
    )
  }

  @SuppressWarnings(Array("UnsafeContains"))
  def oneOf[T <: AnyRef](options: T*): Validator[T] = {
    new NullSafeValidator[T](
      test = options.contains,
      failure = _ -> s"is not one of (${options.mkString(",")})"
    )
  }

  def isTrue[T](constraint: String)(test: T => Boolean): Validator[T] = isTrue[T]((_: T) => constraint)(test)

  def isTrue[T](constraint: T => String)(test: T => Boolean): Validator[T] = new Validator[T] {
    import ViolationBuilder._
    override def apply(value: T): Result = {
      if (test(value)) Success else RuleViolation(value, constraint(value))
    }
  }

  def group(violations: Iterable[Violation]): Result = if (violations.nonEmpty) Failure(violations.to[Set]) else Success

  /**
    * For debugging purposes only.
    * Since the macro removes all logging statements in the validator itself.
    * Usage: info("message") { yourValidator }
    */
  def info[T](message: String)(implicit validator: Validator[T]): Validator[T] = new Validator[T] {
    override def apply(t: T): Result = {
      LoggerFactory.getLogger(Validation.getClass).info(s"Validate: $message on $t")
      validator(t)
    }
  }

  def matchRegexWithFailureMessage(regex: Regex, failureMessage: String): Validator[String] =
    new NullSafeValidator[String](
      test = _.matches(regex.regex),
      failure = _ -> failureMessage
    )

  def validateAll[T](x: T, all: Validator[T]*): Result = all.map(v => validate(x)(v)).fold(Success)(_ and _)

}

object Validation extends Validation {

  /**
    * Marathon internal representation of a constraint violation.
    * @param path a string representation of the path to the violation. E.g. /foo/bla(12)/bar
    * @param constraint the violation
    */
  case class ConstraintViolation(path: String, constraint: String)

  /**
    * Returns a flattened sequence of constraintViolations from a given result.
    * @param result the validation result from accord.
    * @return a flattened list of all violations.
    */
  def allViolations(result: Result): Seq[ConstraintViolation] = {
    def renderPath(desc: Description): String = desc match {
      case Explicit(s) => s
      case Generic(s) => s
      case Indexed(index, of) => s"${renderPath(of)}($index)"
      case AccessChain(elements @ _*) => elements.map(renderPath).mkString("/")
      case _ => ""
    }
    def cleanPath(path: List[Description]): List[Description] = {
      val updatedHead = path match {
        // clean path on access chain
        case AccessChain(elements @ _*) :: tail => AccessChain(cleanPath(elements.toList): _*) :: tail
        // default rule
        case _ => path
      }
      updatedHead match {
        case Nil => Nil
        // filter out index on self
        case head :: Indexed(index, SelfReference) :: tail => Indexed(index, head) :: cleanPath(tail)
        // filter out SelfReference, that is created by valid(validator)
        case head :: SelfReference :: tail => head :: cleanPath(tail)
        case head :: tail => head :: cleanPath(tail)
      }
    }
    def mkPath(path: List[Description]): String = cleanPath(path.reverse).map(renderPath).mkString("/", "/", "")
    def collectViolation(violation: Violation, parents: List[Description] = Nil): Seq[ConstraintViolation] = {
      violation match {
        case RuleViolation(_, constraint, path) => Seq(ConstraintViolation(mkPath(path :: parents), constraint))
        case GroupViolation(_, _, children, path) => children.to[Seq].flatMap(collectViolation(_, path :: parents))
      }
    }
    result match {
      case Success => Seq.empty
      case Failure(violations) => violations.to[Seq].flatMap(collectViolation(_))
    }
  }

  def forAll[T](all: Validator[T]*): Validator[T] = new Validator[T] {
    override def apply(x: T): Result = validateAll(x, all: _*)
  }

  /**
    * Improve legibility of long validation rule sets by removing some of the "wordiness" of using `conditional`
    * (typically followed by `isTrue` (which then tends to be wordy) or some other wordy thing).
    */
  implicit def conditionalTuple[T](t: (T => Boolean, Validator[T])): Validator[T] = conditional(t._1)(t._2)
}
