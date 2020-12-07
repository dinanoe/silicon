package rpi.teacher

import rpi.{Config, Names}
import rpi.inference._
import rpi.util.Namespace
import viper.silver.{ast => sil}

import scala.collection.mutable

/**
  * Builds programs used to check hypotheses.
  *
  * @param teacher The pointer to the teacher.
  */
class CheckBuilder(teacher: Teacher) {
  // import utility methods
  import rpi.util.Statements._

  /**
    * Returns the pointer to the inference.
    *
    * @return The pointer to the inference.
    */
  private def inference: Inference = teacher.inference

  /**
    * Returns the pointer to the original program (labeled).
    *
    * @return The pointer to the original program.
    */
  private def original: sil.Program = inference.labeled

  /**
    * The namespace used to generate unique identifiers.
    */
  private var namespace: Namespace = _

  /**
    * The context information for the example extractor.
    */
  private var context: Context = _

  /**
    * The stack of statement buffers used to accumulate the instrumented statements. The stack is required to properly
    * handle control flow. The topmost buffer accumulates statements for the current branch.
    */
  private var stack: List[mutable.Buffer[sil.Stmt]] = _

  /**
    * Returns a program that performs the given checks.
    *
    * @param checks     The checks to perform.
    * @param hypothesis The hypothesis.
    * @return The program.
    */
  def basicCheck(checks: Seq[sil.Seqn], hypothesis: Hypothesis): (sil.Program, Context) = {
    clear()
    val instrumented = checks.map { check => instrument(check) }
    val program = buildProgram(instrumented, hypothesis)
    // return program and context
    (program, context)
  }

  /**
    * Builds a program performing to the given checks.
    *
    * @param checks The checks.
    * @return The program.
    */
  private def buildProgram(checks: Seq[sil.Seqn], hypothesis: Hypothesis): sil.Program = {
    val domains = Seq.empty
    val fields = original.fields
    val functions = Seq.empty
    val predicates = {
      val existing = original.predicates
      val inferred = inference.predicates(hypothesis)
      existing ++ inferred
    }
    val methods = checks.map { check => builtMethod(check) }
    val extensions = Seq.empty
    val program = sil.Program(domains, fields, functions, predicates, methods, extensions)()
    println(program)
    program
  }

  /**
    * Builds a method performing to the given check.
    *
    * @param check The check.
    * @return The method.
    */
  private def builtMethod(check: sil.Seqn): sil.Method = {
    val name = namespace.uniqueIdentifier(base = "check", Some(0))
    val arguments = Seq.empty
    val returns = Seq.empty
    val preconditions = Seq.empty
    val postconditions = Seq.empty
    val body = {
      val statements = check.ss
      val declarations = check
        .deepCollect { case variable: sil.LocalVar => variable }
        .distinct
        .map { variable => sil.LocalVarDecl(variable.name, variable.typ)() }
      Some(sil.Seqn(statements, declarations)())
    }
    sil.Method(name, arguments, returns, preconditions, postconditions, body)()
  }

  /**
    * Instruments the given sequence.
    *
    * @param sequence The sequence to instrument.
    * @return The instrumented sequence.
    */
  private def instrument(sequence: sil.Seqn): sil.Seqn = {
    push()
    sequence.ss.foreach { statement => instrument(statement) }
    asSequence(pop())
  }

  /**
    * Instruments the given statement.
    *
    * @param statement The statement to instrument.
    */
  private def instrument(statement: sil.Stmt): Unit =
    statement match {
      case sil.If(condition, thenBody, elseBody) =>
        // instrument branches
        val thenInstrumented = instrument(thenBody)
        val elseInstrumented = instrument(elseBody)
        // add instrumented conditional
        val instrumented = sil.If(condition, thenInstrumented, elseInstrumented)()
        addStatement(instrumented)
      case sil.Inhale(predicate: sil.PredicateAccessPredicate) =>
        // get specification instance and adapt predicate
        val instance = getInstance(predicate)
        val adapted = adaptPredicate(predicate, instance)
        // inhale specification
        addInhale(adapted)
        // save state
        val label = saveState(instance)
        context.addInhaled(label, instance)
      case sil.Exhale(predicate: sil.PredicateAccessPredicate) =>
        // get specification instance and adapt predicate
        val instance = getInstance(predicate)
        val adapted = adaptPredicate(predicate, instance)
        // save state
        val label = saveState(instance)
        context.addExhaled(label, instance)
        // exhale specification
        addExhale(adapted)
      case sil.MethodCall(name, arguments, _) =>
        // TODO: Implement me.
        ???
      case _ =>
        addStatement(statement)
    }

  private def getInstance(predicate: sil.PredicateAccessPredicate): Instance = {
    // make sure all arguments are variable accesses
    val access = predicate.loc
    val (arguments, assignments) = {
      val empty = (Seq.empty[sil.LocalVar], Seq.empty[sil.LocalVarAssign])
      access.args.foldLeft(empty) {
        case ((variables, collected), variable: sil.LocalVar) =>
          (variables :+ variable, collected)
        case ((variables, collected), argument) =>
          val name = namespace.uniqueIdentifier(base = "t", Some(0))
          val variable = sil.LocalVar(name, argument.typ)()
          val assignment = sil.LocalVarAssign(variable, argument)()
          (variables :+ variable, collected :+ assignment)
      }
    }

    // TODO: Inhale permissions to make stuff self-framing
    assignments.foreach { assignment => addStatement(assignment) }

    // create and return instance
    val name = access.predicateName
    inference.instance(name, arguments)
  }

  private def adaptPredicate(predicate: sil.PredicateAccessPredicate, instance: Instance): sil.PredicateAccessPredicate = {
    val name = predicate.loc.predicateName
    val arguments = instance.arguments
    val access = sil.PredicateAccess(arguments, name)()
    val permission = predicate.perm
    sil.PredicateAccessPredicate(access, permission)()
  }

  /**
    * Saves the state relevant for the given specification instance.
    *
    * @param instance The instance.
    * @return The label of the state.
    */
  private def saveState(instance: Instance): String = {
    val label = namespace.uniqueIdentifier(base = "s", Some(0))
    // save values of variables
    instance
      .arguments
      .foreach {
        case variable: sil.LocalVar =>
          val name = s"${label}_${variable.name}"
          saveValue(name, variable)
      }
    // save values of atoms
    instance
      .actualAtoms
      .zipWithIndex
      .foreach {
        case (atom, index) =>
          val name = s"${label}_$index"
          saveValue(name, atom)
      }
    // add label
    addStatement(sil.Label(label, Seq.empty)())
    label
  }

  /**
    * Saves the value of the given expression in a variable with the given name.
    *
    * @param name       The name of the variable.
    * @param expression The expression to save.
    */
  private def saveValue(name: String, expression: sil.Exp): Unit = {
    val variable = sil.LocalVar(name, expression.typ)()
    if (Config.useBranching && expression.typ == sil.Bool) {
      // create conditional
      val thenBody = asSequence(sil.LocalVarAssign(variable, sil.TrueLit()())())
      val elseBody = asSequence(sil.LocalVarAssign(variable, sil.FalseLit()())())
      addStatement(sil.If(expression, thenBody, elseBody)())
    } else {
      // create assignment
      addStatement(sil.LocalVarAssign(variable, expression)())
    }
  }

  private def addInhale(predicate: sil.PredicateAccessPredicate): Unit = {
    addStatement(sil.Inhale(predicate)())
    addStatement(sil.Unfold(predicate)())
  }

  private def addExhale(predicate: sil.PredicateAccessPredicate): Unit = {
    addStatement(sil.Fold(predicate)())
    addStatement(sil.Exhale(predicate)())
  }

  private def addStatement(statement: sil.Stmt): Unit =
    stack.head.append(statement)

  private def clear(): Unit = {
    namespace = new Namespace
    context = new Context
    stack = List.empty
  }

  private def push(): Unit =
    stack = mutable.Buffer.empty[sil.Stmt] :: stack

  private def pop(): Seq[sil.Stmt] =
    stack match {
      case head :: tail =>
        stack = tail
        head
    }
}