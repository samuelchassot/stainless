package stainless
package verification

import CoqEncoder._
import CoqExpression._

object optAdmitAll extends inox.FlagOptionDef("admit-all", false)

trait CoqEncoder {
  implicit val debugSection = DebugSectionCoq

  val p: StainlessProgram
  val ctx: inox.Context
  val st: stainless.trees.type = stainless.trees

  import st._
  import p.symbols._
  import p.symbols.CallGraphOrderings._

  // collect the types for which we have no definitions
  // unused for now
  var undefinedTypes = Set[Type]()

  // to give unique names to the arguments we add for preconditions
  var i = 0
  val hypName = "contractHyp"

  def freshId(): CoqIdentifier = {
    i += 1
    CoqIdentifier(FreshIdentifier(hypName + i))
  }

  // ignore flags with an explicit warning
  def ignoreFlags(s: String, flags: Set[Flag]) = {
    if (!flags.isEmpty)
      ctx.reporter.warning(s"Coq translation ignored flags for $s:\n" + flags.mkString(", ") + "\n")
  }

  // transform a Stainless expression into a Coq expression
  def transformTree(t: st.Expr): CoqExpression = t match {
    case MatchExpr(scrut, cases) => 
      transformTree(matchToIfThenElse(t, false))
    case IfExpr(cond, thenn, elze) =>
      ifthenelse(
        transformTree(cond),
        transformType(t.getType),
        CoqLambda(coqUnused, transformTree(thenn)),
        CoqLambda(coqUnused, transformTree(elze)) 
        ) 
    case Variable(id,tpe,flags) =>
      ignoreFlags(t.toString, flags)
      makeFresh(id)
    case ADT(id, targs, args) =>
      Constructor(constructorIdentifier(id), targs.map(transformType) ++ args.map(transformTree))
    case FunctionInvocation(id, targs, args) 
      if (exprOps.preconditionOf(p.symbols.functions(id).fullBody) == None && 
        exprOps.postconditionOf(p.symbols.functions(id).fullBody) == None)
      =>
      CoqApplication(makeFresh(id), targs.map(transformType) ++ args.map(transformTree))
    case FunctionInvocation(id, targs, args) 
      if exprOps.preconditionOf(p.symbols.functions(id).fullBody) == None =>
      CoqApplication(CoqLibraryConstant("proj1_sig"),
        Seq(CoqApplication(makeFresh(id), targs.map(transformType) ++ args.map(transformTree)))
      )
    case FunctionInvocation(id, targs, args) 
      if exprOps.postconditionOf(p.symbols.functions(id).fullBody) == None =>
      CoqApplication(makeFresh(id), targs.map(transformType) ++ args.map(transformTree) :+ CoqUnknown)
    case FunctionInvocation(id, targs, args) =>
      CoqApplication(CoqLibraryConstant("proj1_sig"),
        Seq(CoqApplication(makeFresh(id), targs.map(transformType) ++ args.map(transformTree) :+ CoqUnknown))
      )
    case Application(t, ts) =>
      CoqApplication(transformTree(t), ts.map(transformTree))
    case FiniteSet(args,tpe) =>
      CoqFiniteSet(args map transformTree, transformType(tpe))
    case SetUnion(t1,t2) => CoqSetUnion(transformTree(t1), transformTree(t2))
    case SetIntersection(t1,t2) => CoqSetIntersection(transformTree(t1), transformTree(t2))
    case SetDifference(t1,t2) => CoqSetDifference(transformTree(t1), transformTree(t2))
    case SubsetOf(t1,t2 ) => CoqSetSubset(transformTree(t1), transformTree(t2))
    case ElementOfSet(t1,t2) => CoqBelongs(transformTree(t1), transformTree(t2))
    case Or(ts) => Orb(ts map transformTree)
    case And(ts) => Andb(ts map transformTree)
    case Not(t) => Negb(transformTree(t))
    case Implies(t1,t2) => implb(transformTree(t1), transformTree(t2))
    case Equals(t1,t2) if (t1.getType == IntegerType()) => 
      CoqApplication(CoqLibraryConstant("Zeq_bool"),  Seq(transformTree(t1), transformTree(t2)))
    case Equals(t1,t2) => 
      ctx.reporter.warning(s"Equality for type ${t1.getType} got translated to equality in Coq")
      propInBool(CoqEquals(transformTree(t1),transformTree(t2)))
    case BooleanLiteral(true) => TrueBoolean
    case BooleanLiteral(false) => FalseBoolean
    case ADTSelector(adt, selector) => 
      adt.getType match {
        case ADTType(_,args) => 
          val typeParameters = args.map(transformType)
          CoqApplication(makeFresh(selector), typeParameters :+ transformTree(adt))
        case _ => 
          ctx.reporter.fatalError(s"The translation to Coq failed because $adt does not have an ADT type but ${adt.getType}.")
      }
    case Forall(args, body) =>
      val params = args.map { case vd@ValDef(id,tpe,flags) => 
        ignoreFlags(vd.toString, flags)
        (makeFresh(id), transformType(tpe)) 
      }
      CoqForall(params, CoqEquals(transformTree(body),TrueBoolean))
    case Annotated(body, flags) =>
      ignoreFlags(t.toString, flags.toSet)
      transformTree(body)
    case Let(vd, value, body) =>
      //without type
      CoqLet(makeFresh(vd.id), transformTree(value), transformTree(body))
    case Lambda(vds, body) =>
      vds.foldRight(transformTree(body))((a,b) => CoqLambda(makeFresh(a.id), b) )
    //Integer operations
    case UMinus(e) => CoqApplication(CoqLibraryConstant("Z.opp"), Seq(transformTree(e)))
    case GreaterEquals(e1,e2) =>
      CoqApplication(CoqLibraryConstant("Z.geb"), Seq(transformTree(e1), transformTree(e2)))
    case GreaterThan(e1,e2) =>
      CoqApplication(CoqLibraryConstant("Z.gtb"), Seq(transformTree(e1), transformTree(e2)))
    case LessEquals(e1,e2) =>
      CoqApplication(CoqLibraryConstant("Z.leb"), Seq(transformTree(e1), transformTree(e2)))
    case LessThan(e1,e2) =>
      CoqApplication(CoqLibraryConstant("Z.ltb"), Seq(transformTree(e1), transformTree(e2)))
    case Plus(e1,e2) =>
      CoqApplication(CoqLibraryConstant("Z.add"), Seq(transformTree(e1), transformTree(e2)))
    case Minus(e1,e2) =>
      CoqApplication(CoqLibraryConstant("Z.sub"), Seq(transformTree(e1), transformTree(e2))) 
    case Times(e1,e2) =>
      CoqApplication(CoqLibraryConstant("Z.mul"), Seq(transformTree(e1), transformTree(e2)))
    case Division(e1,e2) => 
      CoqApplication(CoqLibraryConstant("Z.div"), Seq(transformTree(e1), transformTree(e2)))
    case Modulo(e1,e2) =>
      CoqApplication(CoqLibraryConstant("Z.modulo"), Seq(transformTree(e1), transformTree(e2)))
    case Remainder(e1,e2) =>
      CoqApplication(CoqLibraryConstant("Z.rem"), Seq(transformTree(e1), transformTree(e2)))
    case IntegerLiteral(i: BigInt) =>
      CoqZNum(i)
    case Tuple(es) => 
      CoqTuple(es.map(transformTree))

    /**
      * coq encodes tuple type A * B * C as (A * B) * C so we can not write a generic ._2, because in case of
      * (a,b) it would be match t with (_, c) => c end. whereas in case of
      * (a,b,c) it would be match t with (_,b,_) => b end. or snd(fst t)
      *
      * If we encode (a,b,c,...) as pair(a, pair(b, pair(c, ... pair(z, unit) ...))) then we can always say
      *
      * ._1 = fst t
      * ._2 = fst (snd t)
      * ._3 = fst (snd (snd t))
      *
      * The only way this would fail is when we try to access a greater element than the tuple's length, but it
      * will not typecheck in Scala anyway
      *
      */
    case TupleSelect(tuple, idx) =>
      ctx.reporter.warning(s"The translation to Coq assumes tuple $tuple to be a pair")
      if (idx == 1)
        fst(transformTree(tuple))
      else
        snd(transformTree(tuple))
    // case AsInstanceOf(expr, tpe) => transformTree(expr) //ignore asInstanceOf and lets hope it is indeed an instance
    // case IsInstanceOf(expr, tpe) =>
    //   tpe match {
    //     case ADTType(id, args) =>
    //       propInBool(CoqApplication(recognizer(id), (args map transformType) ++ Seq(transformTree(expr)) ))
    //     case _ =>
    //       ctx.reporter.fatalError(s"The translation to Coq does not support recognizers for the type $tpe (${tpe.getClass}).")
    //   }
    case IsConstructor(expr, id) =>
        propInBool(CoqApplication(recognizer(id), getTParams(getConstructor(id)).map(_ => CoqUnknown) ++ Seq(transformTree(expr))))
        /*tpe match {
           case ADTType(id, args) =>
             propInBool(CoqApplication(recognizer(id), (args map transformType) ++ Seq(transformTree(expr)) ))
           case _ =>
             ctx.reporter.fatalError(s"The translation to Coq does not support recognizers for the type $tpe (${tpe.getClass}).")
         }*/
    case Error(tpe, desc) => deriveContradiction //TODO is it ok?

    case _ => 
      ctx.reporter.warning(s"The translation to Coq does not support expression `${t.getClass}` yet: $t.")
      magic(transformType(t.getType))
  }

  // creates a case for a match expression
  def makeFunctionCase(mc: MatchCase): CoqCase = mc match {
    case MatchCase(pattern, None, rhs) => 
      CoqCase(transformPattern(pattern), transformTree(rhs))
    case MatchCase(pattern, _, rhs) =>
      ctx.reporter.warning(s"Guard in match cases are not supported by the Coq translation yet:\n$mc.")
      ctx.reporter.warning(s"This guard was ignored during the translation.")
      CoqCase(transformPattern(pattern), transformTree(rhs))
  }

  // transform patterns that appear in match cases
  def transformPattern(p: Pattern): CoqPattern = p match {
    case a@ADTPattern(_, id, _, subPatterns) => 
      val unusedTypeParameters = (1 to getTParams(sorts(id)).size).map(_ => VariablePattern(None))
      InductiveTypePattern(constructorIdentifier(id), unusedTypeParameters ++ subPatterns.map(transformPattern))
    case WildcardPattern(None) => VariablePattern(None)
    case WildcardPattern(Some(ValDef(id,tpe,flags))) => 
      ignoreFlags(p.toString, flags)
      ctx.reporter.warning(s"Ignoring type $tpe in the wildcard pattern $p.")
      VariablePattern(Some(makeFresh(id)))
      case TuplePattern(None, ps) => CoqTuplePattern(ps.map(transformPattern))
      case TuplePattern(Some(ValDef(id,tpe,flags)), ps) =>
        ignoreFlags(p.toString, flags)
        ctx.reporter.warning(s"Ignoring type $tpe in the wildcard pattern $p.")
        //TODO not tested
        CoqTuplePatternVd(ps.map(transformPattern), VariablePattern(Some(makeFresh(id))))
    case _ => ctx.reporter.fatalError(s"Coq does not support patterns such as `$p` (${p.getClass}) yet.")
  }

  // transforms an ADT into an inductive type
  def transformADT(a: st.ADTSort): CoqCommand = {
    // println("TRANSFORMING")
    // println(a.asString(new PrinterOptions(printUniqueIds = true)))
    // println(CoqIdentifier(a.id).coqString)
    // if (a.root(p.symbols) != a) {
    //   ctx.reporter.debug(s"Skipping $a, since it is not the root of the ADT.")
    //   NoCommand
    // } else {
      // (a match {
      //   case a: st.ADTSort =>
          ignoreFlags(a.toString, a.flags)
          InductiveDefinition(
            makeFresh(a.id),
            a.tparams.map { case p => (CoqIdentifier(p.id), TypeSort) },
            a.constructors.map(c => makeCase(a, c))
          ) $
      //   case a: st.ADTConstructor =>
      //     ignoreFlags(a.toString, a.flags)
      //     InductiveDefinition(
      //       makeFresh(a.id),
      //       a.tparams.map { case p => (CoqIdentifier(p.id), TypeSort) },
      //       Seq(makeCase(a, a))
      //     )
      // }) 
      buildRecognizers(a) $ 
      buildSubTypes(a) $ 
      buildAccessorsForChildren(a)
    // }
  }

  // Define for each constructor of an ADT a function that identifies such elements
  def buildRecognizers(a: Definition): CoqCommand = a match {
    case a: st.ADTSort =>
      manyCommands(a.constructors.map(c => buildRecognizer(a, c)))
    case _ =>
      buildRecognizer(a,a)
  }

  // Define a function that identifies the case of an element of an inductive type
  // and checks that the invariant holds
  def buildRecognizer(root: Definition, constructor: Definition): CoqCommand = constructor match {
    case a: st.ADTConstructor =>
      val element = rawIdentifier("src")
      val tparams = getTParams(constructor).map(t => CoqIdentifier(t.id))
      val extraCase =
        if (root.id.name != constructor.id.name) {
          Some(CoqCase(VariablePattern(None), falseProp))
        }
        else
          None

      NormalDefinition(
        recognizer(a.id),
        getTParams(a).map { case p => (CoqIdentifier(p.id), TypeSort) } ++
          Seq((element, Constructor(makeFresh(root.id), tparams))),
        propSort,
        CoqMatch(element, Seq(
          CoqCase(
            {
              val unusedTypeParameters = (1 to getTParams(a).size).map(_ => VariablePattern(None))
              val unusedFields = (1 to a.fields.size).map(_ => VariablePattern(None))
              InductiveTypePattern(constructorIdentifier(constructor.id), unusedTypeParameters ++ unusedFields)
            },
              trueProp
          )) ++ extraCase
        )
      ) $
      RawCommand(s"Hint Unfold  ${recognizer(a.id).coqString}. \n")
    case _ => NoCommand
  }
            // if (a.hasInvariant) 
            //   CoqApplication(CoqIdentifier(a.invariant.get.id), tparams :+ element)
            // else

  def buildSubTypes(a: ADTSort): CoqCommand = 
      manyCommands(a.constructors.map(c => buildSubType(a, c)))
    // case a: st.ADTSort =>
    // case a: st.ADTConstructor =>
    //   buildSubType(a,a)
  

  def buildSubType(root: ADTSort, constructor: ADTConstructor): CoqCommand = constructor match {
    case a: st.ADTConstructor =>
      val ttparams = root.tparams.map(p => (CoqIdentifier(p.id), TypeSort))
      val tparams = root.tparams.map(t => CoqIdentifier(t.id))
      val element = rawIdentifier("src")
      // println(a.invariant)
      NormalDefinition(
        refinedIdentifier(constructor.id),
        ttparams,
        TypeSort,
        Refinement(
          element,
          CoqApplication(makeFresh(root.id), tparams),
          CoqApplication(recognizer(constructor.id), tparams :+ element)
        )
      ) $
      RawCommand(s"Hint Unfold  ${refinedIdentifier(constructor.id).coqString}. \n")
    case _ => NoCommand
  }

  def buildAccessorsForChildren(a: ADTSort): CoqCommand = 
  // a match {
    // case a: st.ADTSort =>
      manyCommands(a.constructors.map(c => buildAccessors(a, c)))
  //   case c: st.ADTConstructor =>
  //     buildAccessors(c,c)
  // }

  def buildAccessors(root: ADTSort, constructor: ADTConstructor): CoqCommand = constructor match {
    case a: st.ADTConstructor =>
      manyCommands(a.fields.zipWithIndex.map{ case (ValDef(id,tpe,flags),i) => 
        buildAccessor(id,tpe,i,a.fields.size,root,constructor)
      })
    case _ => NoCommand
  }

  def buildAccessor(id: Identifier, tpe: Type, i: Int, n: Int, root: ADTSort, constructor: ADTConstructor): CoqCommand = {
    val element = rawIdentifier("src")
    val extraCase = 
      if (root.id.name != constructor.id.name)
        Some(CoqCase(VariablePattern(None), deriveContradiction))
      else
        None
    val tparams = root.tparams.map { case p => (CoqIdentifier(p.id), TypeSort) } 

    NormalDefinition(
      makeFresh(id),
        tparams ++
        Seq(((element, CoqApplication(refinedIdentifier(constructor.id), root.tparams.map(t => CoqIdentifier(t.id)))))),
      transformType(tpe),
      CoqMatch(element, 
        Seq(
          CoqCase(
            {
              val unusedTypeParameters = (1 to getTParams(constructor).size).map(_ => VariablePattern(None))
              val fields = (0 to n-1).map(i => VariablePattern(Some(rawIdentifier("f" + i))))
              InductiveTypePattern(constructorIdentifier(constructor.id), unusedTypeParameters ++ fields)
            },
            rawIdentifier("f" + i)
          )
        ) ++ extraCase
      )
    )
  }

  // creates a case for an inductive type
  def makeCase(root: Definition, a: Definition) = a match { 
    case a: ADTConstructor =>
      // ignoreFlags(a.toString, a.flags)
      val fieldsTypes = a.fields.map(vd => transformType(vd.tpe))
      val arrowType = fieldsTypes.foldRight[CoqExpression](
        Constructor(makeFresh(root.id), getTParams(a).map(t => CoqIdentifier(t.id)))) // the inductive type
        { case (field, acc) => Arrow(field, acc)} // the parameters of the constructor
      InductiveCase(constructorIdentifier(a.id), arrowType)
    case _ =>
      ctx.reporter.fatalError(s"The translation to Coq does not support $a as a constructor.")
  }



  // transform function definitions
  def transformFunction(fd: st.FunDef): CoqCommand = {
    ignoreFlags(fd.toString, fd.flags)
    val mutual = p.symbols.functions.find{ case (_,fd2) => fd != fd2 && transitivelyCalls(fd, fd2) && transitivelyCalls(fd2, fd) }
    if (mutual.isDefined)
      ctx.reporter.fatalError(s"The translation to Coq does not support mutual recursion (between ${fd.id.name} and ${mutual.get._1.name})")
    else {
      val tparams: Seq[(CoqIdentifier,CoqExpression)] = fd.tparams.map { case p => (CoqIdentifier(p.id), TypeSort) }
      val params: Seq[(CoqIdentifier,CoqExpression)] = fd.params.map { case vd => (makeFresh(vd.id), transformType(vd.tpe)) }
      val body = exprOps.withoutSpecs(fd.fullBody) match {
        case None => ctx.reporter.fatalError(s"We do not support functions with empty bodies: ${fd.id.name}")
        case Some(b) => transformTree(b)
      }
      val preconditionName = freshId()
      val preconditionParam: Seq[(CoqIdentifier,CoqExpression)] = exprOps.preconditionOf(fd.fullBody) match {
        case None => Seq()
        case Some(p) => Seq((preconditionName, transformTree(p) === TrueBoolean))
      }
      val returnType = exprOps.postconditionOf(fd.fullBody) match {
        case None => transformType(fd.returnType)
        case Some(Lambda(Seq(vd), post)) => 
          Refinement(makeFresh(vd.id), transformType(vd.tpe), transformTree(post) === TrueBoolean)
      }
      val allParams = tparams ++ params ++ preconditionParam
      val tmp = (if (fd.isRecursive) {
        FixpointDefinition(makeFresh(fd.id), allParams, returnType, body)
      } else {
        NormalDefinition(makeFresh(fd.id), allParams, returnType, body)
      })
      tmp
      //if (ctx.options.findOptionOrDefault(optAdmitAll)) {
      /*if (fd.flags.contains("library")) {
        tmp $
        RawCommand("Admit Obligations.")
      } else {
        tmp
      }*/
    }
    // ctx.reporter.internalError("The translation to Coq does not support Functions yet.")
  }



  // translate a Stainless type to a Coq type
  def transformType(tpe: st.Type): CoqExpression = tpe match {
    case UnitType() => CoqUnit
    case ADTType(id, args) if (sorts.contains(id)) => 
      CoqApplication(makeFresh(id), args map transformType)
    case ADTType(id, args) => 
      refinedIdentifier(id)((args map transformType): _*) 
    case TypeParameter(id,flags) => 
      ignoreFlags(tpe.toString, flags)
      CoqIdentifier(id)
    case BooleanType() => CoqBool
    case FunctionType(ts, t) => 
      val tts = ts.map(transformType)
      tts.foldRight[CoqExpression](transformType(t))
        { case (arg,acc) => Arrow(arg,acc) }
    case SetType(base) =>
      CoqSetType(transformType(base))
    case IntegerType() => CoqZ
    case BVType(_) =>
      ctx.reporter.warning(s"The translation to Coq currently converts the type $tpe (${tpe.getClass}) to BigInt.")
      CoqZ
    case MapType(u, v) => mapType(transformType(u), transformType(v))
    case TupleType(ts) => CoqTupleType(ts map transformType)
    case _ => 
      ctx.reporter.fatalError(s"The translation to Coq does not support the type $tpe (${tpe.getClass}).")
      //magic(typeSort)
  }

  // finds an order in which to define the functions
  // does not work for mutually recursive functions
  // highly non optimized
  def transformFunctionsInOrder(fds: Seq[FunDef]): CoqCommand = {
    if (fds.isEmpty) NoCommand
    else {
      val f = fds.find { fd =>
        fds.forall { fd2 =>
          fd == fd2 || !transitivelyCalls(fd,fd2)
        }
      }
      f match {
        case Some(fd) => 
          // println("found first function: " + fd.id)
          transformFunction(fd) $ transformFunctionsInOrder(fds.filterNot(_ == fd))
        case None => 
          ctx.reporter.warning(s"Coq translation: mutual recursion is not supported yet (" + fds.map(_.id).mkString(",") + ").")
          NoCommand
      }
    }
  }

  def makeLibTactic(adts: Seq[Definition]) = {
    RawCommand("""
 Ltac libStep := match goal with
   | [ H: context[match ?t with _ => _ end] |- _ ] => destruct t
   | [ H: ex _ _ |- _ ] => destruct H
   |   H: exists _, _ |- _ => destruct H
   | [ |- context[match ?t with _ => _ end]] =>
       let matched := fresh "matched" in
       destruct t eqn:matched
   | [ |- context[if ?t then _ else _]] =>
       let ifexpr := fresh "ifexpr" in
       destruct t eqn:ifexpr
   | [ H: context[match ?t with _ => _ end] |- _ ] =>
       let matched := fresh "matched" in
       destruct t eqn:matched
   | [ H: context[if ?t then _ else _] |- _ ] =>
       let ifexpr := fresh "ifexpr" in
       destruct t eqn:ifexpr
   | _ =>
       congruence || 
       simpl in * || 
       program_simpl || 
       intuition || 
       omega || 
       eauto || 
       discriminate ||
       (autounfold in *) ||
       (rewrite <- Zgt_is_gt_bool in *) ||
       (rewrite Z.geb_le in *)
   end.

   Obligation Tactic := repeat libStep.""")

//| [ H: isCons _ ?L |- _ ] => is_var L; destruct L
//       unfold Cons_type in * ||

  }


  def makeTactic(adts: Seq[Definition]) = {
    RawCommand("""
   Ltac step := libStep.

   Obligation Tactic := repeat step.""")

  }

  def header(): CoqCommand = {
    RequireImport("Coq.Program.Tactics") $
      RequireImport("Coq.Program.Program") $
      RequireImport("Coq.Lists.List") $
      RequireImport("Coq.Lists.ListSet") $
      RequireImport("Coq.Logic.Classical") $
      RequireImport("Omega") $
      OpenScope("bool_scope") $
      RawCommand("""Axiom classicT: forall P: Prop, P + ~P.""") $
      //TODO should be deducible from the previous one
      RawCommand("""Axiom Aeq_dec_all: forall T: Type, forall x y: T, {x = y} + {x <> y}.""") $
      RawCommand("""Axiom unsupported: False. """) $
      RawCommand(""" Axiom map_type: Type -> Type -> Type.""") $
      RawCommand( """Definition propInBool (P: Prop): bool :=
                    | if (classicT P)
                    | then true
                    | else false.
                    |""".stripMargin) $
      RawCommand("""Hint Unfold propInBool.""")$
      RawCommand("""Definition ifthenelse b A (e1: b = true -> A) (e2: b = false -> A): A.
                 | destruct b.
                 | - apply e1. reflexivity.
                 | - apply e2. reflexivity.
                 Qed.""".stripMargin) $
      RawCommand( """Definition boolInProp (b: bool): Prop := b = true.""") $
      RawCommand( """Coercion boolInProp: bool >-> Sortclass.""") $
      RawCommand( """ Definition set_subset {T: Type} (a b: set T): bool :=
                        propInBool ((set_diff (Aeq_dec_all _) a b) = empty_set T).""".stripMargin)$
      RawCommand( """ Definition magic (T: Type): T := match unsupported with end.""") $
      RawCommand( """Set Default Timeout 10.""")
  }

  def transformLib(): CoqCommand = {
    header() $
    makeLibTactic(p.symbols.sorts.values.filter(_.flags.contains("library")).toSeq)$
    manyCommands(p.symbols.sorts.values.filter(_.flags.contains("library")).toSeq.map(transformADT)) $
    transformFunctionsInOrder(p.symbols.functions.values.filter(_.flags.contains("library")).toSeq)

  }

  def transform(): CoqCommand = {
    //TODO not ideal
    RawCommand("Load verif1.") $
    makeTactic(p.symbols.sorts.values.filter(!_.flags.contains("library")).toSeq)$
    manyCommands(p.symbols.sorts.values.filter(!_.flags.contains("library")).toSeq.map(transformADT)) $
    transformFunctionsInOrder(p.symbols.functions.values.filter(!_.flags.contains("library")).toSeq)
  }

  def getTParams(a: Definition) = a match {
    case a: ADTConstructor => sorts(a.sort).tparams
    case a: ADTSort => a.tparams
    case _ => throw new Exception("getTParams " + a)
  }
}

object CoqEncoder {


  val freshIdName = "tmp"
  var m = Map[Identifier, CoqIdentifier] ()
  var count = Map[String, Int]()

  def makeFresh(id: Identifier): CoqIdentifier = {
    if (m.contains(id)) m(id)
    else {
      val i = count.getOrElse(id.name,0)
      val freshName = if (i == 0) id.name else id.name + i
        count = count.updated(id.name, i +1)
      val res = CoqIdentifier(new Identifier(freshName, id.id, id.globalId))
      m = m.updated(id, res)
      res
    } 
  }

  def makeFresh(): CoqIdentifier = {

    val i = count.getOrElse(freshIdName,0)
    val freshName = if (i == 0) freshIdName else freshIdName + i
    count = count.updated(freshIdName, i +1)
    CoqIdentifier(FreshIdentifier(freshName))
  }
  

  def deriveContradiction = RawExpression("""let contradiction: False := _ in match contradiction with end""")

  def unsupportedExpression = RawExpression("""match unsupported with end""")
  //def unsupportedExpression = RawExpression("""magic""")

  def constructorIdentifier(i: Identifier): CoqIdentifier = {
    CoqIdentifier(new Identifier(i.name + "_construct", i.id, i.globalId))
  }

  def refinedIdentifier(i: Identifier): CoqIdentifier = {
    CoqIdentifier(new Identifier(i.name + "_type", i.id, i.globalId))
  }

  def recognizer(i: Identifier): CoqIdentifier = {
    CoqIdentifier(new Identifier("is" + i.name, i.id, i.globalId))
  }

  def rawIdentifier(s: String): CoqIdentifier = {
    CoqIdentifier(new Identifier(s,0,0))
  }

  def manyCommands(l: Seq[CoqCommand]): CoqCommand = {
    if (l.isEmpty) NoCommand
    else l.tail.foldLeft(l.head)(_ $ _)
  }

  def transformProgram(program: StainlessProgram, context: inox.Context) = {
    object encoder extends CoqEncoder {
      val p = program
      val ctx = context
    }

    (encoder.transformLib(), encoder.transform())
  }
}