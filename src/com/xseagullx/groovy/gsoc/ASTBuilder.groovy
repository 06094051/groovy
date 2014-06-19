package com.xseagullx.groovy.gsoc

import com.xseagullx.groovy.gsoc.GroovyParser.ArgumentListContext
import com.xseagullx.groovy.gsoc.util.StringUtil
import groovyjarjarasm.asm.Opcodes
import org.antlr.v4.runtime.ANTLRInputStream
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.misc.NotNull
import org.antlr.v4.runtime.tree.ParseTree
import org.antlr.v4.runtime.tree.ParseTreeWalker
import org.antlr.v4.runtime.tree.TerminalNode
import org.codehaus.groovy.ast.*
import org.codehaus.groovy.ast.expr.*
import org.codehaus.groovy.ast.stmt.*
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.syntax.Numbers
import org.codehaus.groovy.syntax.SyntaxException
import org.codehaus.groovy.syntax.Token
import org.codehaus.groovy.syntax.Types

import java.lang.reflect.Modifier

class ASTBuilder extends GroovyBaseListener {
    ModuleNode moduleNode

    private SourceUnit sourceUnit
    private ClassLoader classLoader

    ASTBuilder(SourceUnit sourceUnit, ClassLoader classLoader) {
        this.classLoader = classLoader
        this.sourceUnit = sourceUnit
        moduleNode = new ModuleNode(sourceUnit)

        def lexer = new GroovyLexer(new ANTLRInputStream(StringUtil.replaceHexEscapes(sourceUnit.source.reader.text)))
        CommonTokenStream tokens = new CommonTokenStream(lexer)
        def parser = new GroovyParser(tokens)
        ParseTree tree = parser.compilationUnit()

        try {
            new ParseTreeWalker().walk(this, tree);
        }
        catch (CompilationFailedException ignored) {
            // Compilation failed.
        }
    }

    @Override void exitImportStatement(@NotNull GroovyParser.ImportStatementContext ctx) {
        ImportNode node
        if (ctx.getChild(ctx.childCount - 1).text == '*') {
            moduleNode.addStarImport(ctx.IDENTIFIER().join('.') + '.')
            node = moduleNode.starImports.last()
        }
        else {
            moduleNode.addImport(ctx.IDENTIFIER()[-1].text, ClassHelper.make(ctx.IDENTIFIER().join('.')), parseAnnotations(ctx.annotationClause()) )
            node = moduleNode.imports.last()
            setupNodeLocation(node.type, ctx)
        }
        setupNodeLocation(node, ctx)
    }

    @Override void enterPackageDefinition(@NotNull GroovyParser.PackageDefinitionContext ctx) {
        moduleNode.packageName = ctx.IDENTIFIER().join('.') + '.'
        attachAnnotations(moduleNode.package, ctx.annotationClause())
        setupNodeLocation(moduleNode.package, ctx)
    }

    @Override void exitClassDeclaration(@NotNull GroovyParser.ClassDeclarationContext ctx) {
        def classNode = new ClassNode("${moduleNode.packageName ?: ""}${ctx.IDENTIFIER()}", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
        setupNodeLocation(classNode, ctx)
        attachAnnotations(classNode, ctx.annotationClause())
        moduleNode.addClass(classNode)
        if (ctx.KW_EXTENDS())
            classNode.setSuperClass(parseExpression(ctx.genericClassNameExpression()[0]))
        if (ctx.KW_IMPLEMENTS())
            classNode.setInterfaces(ctx.genericClassNameExpression()[(ctx.KW_EXTENDS() ? 1 : 0)..-1].collect { parseExpression(it) } as ClassNode[])

        classNode.genericsTypes = parseGenericDeclaration(ctx.genericDeclarationList())
        classNode.usingGenerics = classNode.genericsTypes || classNode.superClass.usingGenerics || classNode.interfaces.any { it.usingGenerics }
        classNode.modifiers = parseClassModifiers(ctx.classModifier())
        classNode.syntheticPublic = (classNode.modifiers & Opcodes.ACC_SYNTHETIC) != 0
        classNode.modifiers &= ~Opcodes.ACC_SYNTHETIC // FIXME Magic with synthetic modifier.

        parseMembers(classNode, ctx.classMember())
    }

    def parseMembers(ClassNode classNode, List<GroovyParser.ClassMemberContext> ctx) {
        for (member in ctx) {
            def memberContext = member.children[-1]
            assert memberContext instanceof GroovyParser.ConstructorDeclarationContext ||
                    memberContext instanceof GroovyParser.MethodDeclarationContext ||
                    memberContext instanceof GroovyParser.FieldDeclarationContext ||
                    memberContext instanceof GroovyParser.ObjectInitializerContext ||
                    memberContext instanceof GroovyParser.ClassInitializerContext

            // This inspection is suppressed cause I use Runtime multimethods dispatching mechanics of Groovy.
            //noinspection GroovyAssignabilityCheck
            def memberNode = parseMember(classNode, memberContext)
            if (memberNode)
                setupNodeLocation(memberNode, member)
            if (member.childCount > 1)
            {
                assert memberNode instanceof AnnotatedNode
                for (annotationCtx in member.children[0..-2]) {
                    assert annotationCtx instanceof GroovyParser.AnnotationClauseContext
                    memberNode.addAnnotation(parseAnnotation(annotationCtx))
                }
            }
        }
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    AnnotatedNode parseMember(ClassNode classNode, GroovyParser.MethodDeclarationContext ctx) {
        //noinspection GroovyAssignabilityCheck
        def (int modifiers, boolean hasVisibilityModifier) = parseModifiers(ctx.memberModifier(), Opcodes.ACC_PUBLIC)
        def statement = parseStatement(ctx.blockStatement() as GroovyParser.BlockStatementContext)

        def params = parseParameters(ctx.argumentDeclarationList())

        def returnType = ctx.typeDeclaration() ? parseTypeDeclaration(ctx.typeDeclaration()) :
            ctx.genericClassNameExpression() ? parseExpression(ctx.genericClassNameExpression()) : ClassHelper.OBJECT_TYPE

        def exceptions = parseThrowsClause(ctx.throwsClause())
        def methodNode = classNode.addMethod(ctx.IDENTIFIER().text, modifiers, returnType, params, exceptions, statement)
        methodNode.genericsTypes = parseGenericDeclaration(ctx.genericDeclarationList())

        setupNodeLocation(methodNode, ctx)
        attachAnnotations(methodNode, ctx.annotationClause())
        methodNode.syntheticPublic = !hasVisibilityModifier
        methodNode
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    AnnotatedNode parseMember(ClassNode classNode, GroovyParser.FieldDeclarationContext ctx) {
        //noinspection GroovyAssignabilityCheck
        def (int modifiers, boolean hasVisibilityModifier) = parseModifiers(ctx.memberModifier())

        def typeDeclaration = ctx.genericClassNameExpression() ? parseExpression(ctx.genericClassNameExpression()) : ClassHelper.OBJECT_TYPE
        AnnotatedNode node
        if (hasVisibilityModifier) {
            def field = classNode.addField(ctx.IDENTIFIER().text, modifiers, typeDeclaration, null)
            attachAnnotations(field, ctx.annotationClause())
            node = setupNodeLocation(field, ctx)
        }
        else { // no visibility specified. Generate property node.
            def propertyModifier = modifiers | Opcodes.ACC_PUBLIC
            def propertyNode = classNode.addProperty(ctx.IDENTIFIER().text, propertyModifier, typeDeclaration, null, null, null)
            propertyNode.field.modifiers = modifiers | Opcodes.ACC_PRIVATE
            propertyNode.field.synthetic = true
            node = setupNodeLocation(propertyNode.field, ctx)
            attachAnnotations(propertyNode.field, ctx.annotationClause())
            setupNodeLocation(propertyNode, ctx)
        }
        node
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static void parseMember(ClassNode classNode, GroovyParser.ClassInitializerContext ctx) {
        (getOrCreateClinitMethod(classNode).code as BlockStatement).addStatement(parseStatement(ctx.blockStatement()))
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static void parseMember(ClassNode classNode, GroovyParser.ObjectInitializerContext ctx) {
        def statement = new BlockStatement()
        statement.addStatement(parseStatement(ctx.blockStatement()))
        classNode.addObjectInitializerStatements(statement)
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static AnnotatedNode parseMember(ClassNode classNode, GroovyParser.ConstructorDeclarationContext ctx) {
        int modifiers = ctx.VISIBILITY_MODIFIER() ? parseVisibilityModifiers(ctx.VISIBILITY_MODIFIER()) : Opcodes.ACC_PUBLIC

        def exceptions = parseThrowsClause(ctx.throwsClause())
        def constructorNode = classNode.addConstructor(modifiers, parseParameters(ctx.argumentDeclarationList()), exceptions, parseStatement(ctx.blockStatement() as GroovyParser.BlockStatementContext))
        setupNodeLocation(constructorNode, ctx)
        constructorNode.syntheticPublic = ctx.VISIBILITY_MODIFIER() == null
        constructorNode
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Statements.
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////


    static Statement parseStatement(GroovyParser.StatementContext ctx) {
        throw new RuntimeException("Unsupported statement type! $ctx.text")
    }

    static Statement parseStatement(GroovyParser.BlockStatementContext ctx) {
        def statement = new BlockStatement()
        if (!ctx)
            return statement

        ctx.statement().each {
            statement.addStatement(parseStatement(it))
        }
        setupNodeLocation(statement, ctx)
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static Statement parseStatement(GroovyParser.ExpressionStatementContext ctx) {
        setupNodeLocation(new ExpressionStatement(parseExpression(ctx.expression())), ctx)
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static Statement parseStatement(GroovyParser.IfStatementContext ctx) {
        def trueBranch = new BlockStatement()
        def falseBranch = null
        def s = trueBranch
        for (c in ctx.children) {
            if (c instanceof GroovyParser.StatementContext) {
                s.addStatement(parseStatement(c))
            }
            else if (c instanceof TerminalNode) {
                if (c.symbol.type == GroovyLexer.KW_ELSE) {
                    falseBranch = new BlockStatement()
                    s = falseBranch
                }

            }
        }

        if (!falseBranch)
            falseBranch = EmptyStatement.INSTANCE

        def expression = new BooleanExpression(parseExpression(ctx.expression()))
        setupNodeLocation(new IfStatement(expression, trueBranch, falseBranch), ctx)
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static Statement parseStatement(GroovyParser.WhileStatementContext ctx) {
        def statement = new BlockStatement() // TODO refactor block statement creation. label #BSC
        for (stmt in ctx.statement())
            statement.addStatement parseStatement(stmt)

        setupNodeLocation(new WhileStatement(new BooleanExpression(parseExpression(ctx.expression())), statement), ctx)
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static Statement parseStatement(GroovyParser.ClassicForStatementContext ctx) {
        def statement = new BlockStatement() // #BSC
        for (stmt in ctx.statement())
            statement.addStatement parseStatement(stmt)

        def expression = new ClosureListExpression()

        def captureNext = false
        for (c in ctx.children) {
            // FIXME terrible logic.
            def isSemicolon = c instanceof TerminalNode && (c.symbol.text == ';' || c.symbol.text == '(' || c.symbol.text == ')')
            if (captureNext && isSemicolon)
                expression.addExpression(EmptyExpression.INSTANCE)
            else if (captureNext && c instanceof GroovyParser.ExpressionContext)
                expression.addExpression(parseExpression(c))
            captureNext = isSemicolon
        }

        def parameter = ForStatement.FOR_LOOP_DUMMY
        setupNodeLocation(new ForStatement(parameter, expression, statement), ctx)
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static Statement parseStatement(GroovyParser.ForInStatementContext ctx) {
        def parameter = new Parameter(parseTypeDeclaration(ctx.typeDeclaration()), ctx.IDENTIFIER().text)
        parameter = setupNodeLocation(parameter, ctx.IDENTIFIER().symbol)

        def statement = new BlockStatement() // #BSC
        for (stmt in ctx.statement())
            statement.addStatement parseStatement(stmt)

        setupNodeLocation(new ForStatement(parameter, parseExpression(ctx.expression()), statement), ctx)
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static Statement parseStatement(GroovyParser.SwitchStatementContext ctx) {
        List<CaseStatement> caseStatements = []
        for (caseStmt in ctx.caseStatement()) {
            def stmt = new BlockStatement() // #BSC
            for (GroovyParser.StatementContext st in caseStmt.statement())
                stmt.addStatement(parseStatement(st))

            caseStatements << setupNodeLocation(new CaseStatement(parseExpression(caseStmt.expression()), stmt),
                    caseStmt.KW_CASE().symbol) // There only 'case' kw was highlighted in parser old version.
        }

        Statement defaultStatement
        if (ctx.KW_DEFAULT()) {
            defaultStatement = new BlockStatement() // #BSC
            for (GroovyParser.StatementContext stmt in ctx.statement())
                defaultStatement.addStatement(parseStatement(stmt))
        }
        else
            defaultStatement = EmptyStatement.INSTANCE // TODO Refactor empty stataements and expressions.

        new SwitchStatement(parseExpression(ctx.expression()), caseStatements, defaultStatement)
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static Statement parseStatement(GroovyParser.ControlStatementContext ctx) {
        // TODO check validity. Labeling support.
        // Fake inspection result should be suppressed.
        //noinspection GroovyConditionalWithIdenticalBranches
        setupNodeLocation( ctx.KW_BREAK() ? new BreakStatement() : new ContinueStatement() , ctx)
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static Statement parseStatement(GroovyParser.ReturnStatementContext ctx) {
        def expression = ctx.expression()
        setupNodeLocation(new ReturnStatement(expression ? parseExpression(expression) : EmptyExpression.INSTANCE), ctx)
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static Statement parseStatement(GroovyParser.ThrowStatementContext ctx) {
        setupNodeLocation(new ThrowStatement(parseExpression(ctx.expression())), ctx)
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static Statement parseStatement(GroovyParser.TryCatchFinallyStatementContext ctx) {
        def finallyStatement

        GroovyParser.BlockStatementContext finallyBlockStatement = ctx.finallyBlock()?.blockStatement()
        if (finallyBlockStatement) {
            def fbs = new BlockStatement()
            fbs.addStatement(parseStatement(finallyBlockStatement))
            finallyStatement = setupNodeLocation(fbs, finallyBlockStatement)

        }
        else
            finallyStatement = EmptyStatement.INSTANCE

        def statement = new TryCatchStatement(parseStatement(ctx.tryBlock().blockStatement() as GroovyParser.BlockStatementContext), finallyStatement)
        ctx.catchBlock().each {
            def catchBlock = parseStatement(it.blockStatement() as GroovyParser.BlockStatementContext)
            def var = it.IDENTIFIER().text

            def classNameExpression = it.classNameExpression()
            if (!classNameExpression)
                statement.addCatch(setupNodeLocation(new CatchStatement(new Parameter(ClassHelper.OBJECT_TYPE, var), catchBlock), it))
            else {
                classNameExpression.each {
                    statement.addCatch(setupNodeLocation(new CatchStatement(new Parameter(parseExpression(it as GroovyParser.ClassNameExpressionContext), var), catchBlock), it))
                }
            }
        }
        statement
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static Statement parseStatement(GroovyParser.CommandExpressionStatementContext ctx) {
        Expression expression = null
        def list = ctx.cmdExpressionRule().children.collate(2)
        for (c in list) {
            def (c1, c0) = c
            if (c.size() == 1)
                expression = new PropertyExpression(expression, c1.text as String)
            else {
                assert c0 instanceof ArgumentListContext
                if (c1 instanceof TerminalNode) {
                    expression = new MethodCallExpression(expression, c1.text, createArgumentList(c0))
                    expression.implicitThis = false
                }
                else if (c1 instanceof GroovyParser.PathExpressionContext) {
                    String methodName
                    boolean implicitThis
                    //noinspection GroovyAssignabilityCheck
                    (expression, methodName, implicitThis) = parsePathExpression(c1)

                    expression = new MethodCallExpression(expression, methodName, createArgumentList(c0))
                    expression.implicitThis = implicitThis
                }
            }
        }

        println("!> $expression.text")
        new ExpressionStatement(expression)
    }

    /**
     * Parse path expression.
     * @param ctx
     * @return tuple of 3 values: Expression, String methodName and boolean implicitThis flag.
     */
    static def parsePathExpression(GroovyParser.PathExpressionContext ctx) {
        Expression expression
        def identifiers = ctx.IDENTIFIER() as List<TerminalNode>
        switch (identifiers.size()) {
            case 1: expression = VariableExpression.THIS_EXPRESSION; break
            case 2: expression = new VariableExpression(identifiers[0].text); break
            default: expression = identifiers[1..-2].inject(new VariableExpression(identifiers[0].text)) { Expression expr, prop ->
                new PropertyExpression(expr, prop.text)
            }; break
        }
        [expression, identifiers[-1], identifiers.size() == 1]
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Expressions.
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    static Expression parseExpression(GroovyParser.ExpressionContext ctx) {
        throw new RuntimeException("Unsupported expression type! $ctx")
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static Expression parseExpression(GroovyParser.ParenthesisExpressionContext ctx) {
        parseExpression(ctx.expression())
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static Expression parseExpression(GroovyParser.ListConstructorContext ctx) {
        def expression = new ListExpression(ctx.expression().collect(ASTBuilder.&parseExpression))
        setupNodeLocation(expression, ctx)
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static Expression parseExpression(GroovyParser.MapConstructorContext ctx) {
        setupNodeLocation(new MapExpression(ctx.mapEntry()?.collect(ASTBuilder.&parseExpression) ?: []), ctx)
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static Expression parseExpression(GroovyParser.MapEntryContext ctx) {
        Expression keyExpr, valueExpr
        def expressions = ctx.expression()
        if (expressions.size() == 1) {
            def key = ctx.IDENTIFIER() ? ctx.IDENTIFIER().text : parseString(ctx.STRING())
            keyExpr = new ConstantExpression(key)
            valueExpr = parseExpression(expressions[0])
        }
        else {
            keyExpr = parseExpression(expressions[0])
            valueExpr = parseExpression(expressions[1])
        }
        setupNodeLocation(new MapEntryExpression(keyExpr, valueExpr), ctx)
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static Expression parseExpression(GroovyParser.ClosureExpressionContext ctx) {
        parseExpression(ctx.closureExpressionRule())
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static Expression parseExpression(GroovyParser.ClosureExpressionRuleContext ctx) {
        def parameters = parseParameters(ctx.argumentDeclarationList())

        def statement = parseStatement(ctx.blockStatement() as GroovyParser.BlockStatementContext)
        setupNodeLocation(new ClosureExpression(parameters ?: null, statement), ctx)
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static Expression parseExpression(GroovyParser.BinaryExpressionContext ctx) {
        def op = createToken(ctx.getChild(1) as TerminalNode)
        def expression
        def left = parseExpression(ctx.expression(0))
        def right = null // Will be initialized later, in switch. We should handle as and instanceof creating
        // ClassExpression for given IDENTIFIERS. So, switch should fall through.
        //noinspection GroovyFallthrough
        switch (op.type) {
            case Types.RANGE_OPERATOR:
                right = parseExpression(ctx.expression(1))
                expression = new RangeExpression(left, right, !op.text.endsWith('<'))
                break;
            case Types.KEYWORD_AS:
                def classNode = setupNodeLocation(parseExpression(ctx.genericClassNameExpression()), ctx.genericClassNameExpression())
                expression = CastExpression.asExpression(classNode, left)
                break;
            case Types.KEYWORD_INSTANCEOF:
                def classNode = setupNodeLocation(parseExpression(ctx.genericClassNameExpression()), ctx.genericClassNameExpression())
                right = new ClassExpression(classNode)
            default:
                if (!right)
                    right = parseExpression(ctx.expression(1))
                expression = new BinaryExpression(left, op, right)
                break
        }

        expression.columnNumber = op.startColumn
        expression.lastColumnNumber = op.startColumn + op.text.length()
        expression.lineNumber = op.startLine
        expression.lastLineNumber = op.startLine
        expression
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static Expression parseExpression(GroovyParser.UnaryExpressionContext ctx) {
        def node = null
        def op = ctx.getChild(0) as TerminalNode
        switch (op.text) {
            case '-' : node = new UnaryMinusExpression(parseExpression(ctx.expression())); break
            case '+' : node = new UnaryPlusExpression(parseExpression(ctx.expression())); break
            case '!' : node = new NotExpression(parseExpression(ctx.expression())); break
            case '~' : node = new BitwiseNegationExpression(parseExpression(ctx.expression())); break
            default: assert false, "There is no $op.text handler."; break
        }

        node.columnNumber = op.symbol.charPositionInLine + 1
        node.lineNumber = op.symbol.line
        node.lastLineNumber = op.symbol.line
        node.lastColumnNumber = op.symbol.charPositionInLine + 1 + op.text.length()
        node
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static Expression parseExpression(GroovyParser.VariableExpressionContext ctx) {
        setupNodeLocation(new VariableExpression(ctx.IDENTIFIER().text), ctx)
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static Expression parseExpression(GroovyParser.FieldAccessExpressionContext ctx) {
        def op = ctx.getChild(1) as TerminalNode
        def text = ctx.IDENTIFIER().text
        def left = parseExpression(ctx.expression())
        def right = new ConstantExpression(text)
        def node
        if (op.text == '.@')
            node = new AttributeExpression(left, right)
        else {
            node = new PropertyExpression(left, right, ctx.getChild(1).text in ['?.', '*.'])
        }
        setupNodeLocation(node, ctx)
        node.spreadSafe = op.text == '*.'
        node
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static PrefixExpression parseExpression(GroovyParser.PrefixExpressionContext ctx) {
        setupNodeLocation(new PrefixExpression(createToken(ctx.getChild(0) as TerminalNode), parseExpression(ctx.expression())), ctx)
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static PostfixExpression parseExpression(GroovyParser.PostfixExpressionContext ctx) {
        setupNodeLocation(new PostfixExpression(parseExpression(ctx.expression()), createToken(ctx.getChild(1) as TerminalNode)), ctx)
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static ConstantExpression parseExpression(GroovyParser.ConstantDecimalExpressionContext ctx) {
        def text = ctx.DECIMAL().text
        setupNodeLocation(new ConstantExpression(Numbers.parseDecimal(text), !text.startsWith('-')), ctx) // Why 10 is int but -10 is Integer?
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static ConstantExpression parseExpression(GroovyParser.ConstantIntegerExpressionContext ctx) {
        def text = ctx.INTEGER().text
        setupNodeLocation(new ConstantExpression(Numbers.parseInteger(text), !text.startsWith('-')), ctx) //Why 10 is int but -10 is Integer?
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static ConstantExpression parseExpression(GroovyParser.BoolExpressionContext ctx) {
        setupNodeLocation(new ConstantExpression(ctx.KW_FALSE() ? false : true, true), ctx)
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static ConstantExpression parseExpression(GroovyParser.ConstantExpressionContext ctx) {
        def text = ctx.text

        //Remove start and end quotes.
        if (text.startsWith(/'''/) || text.startsWith(/"""/))
            text = text.length() == 6 ? '' : text[3..-4]
        else if (text.startsWith(/'/) || text.startsWith(/"/))
            text = text.length() == 2 ? '' : text[1..-2]

        //Find escapes.
        text = StringUtil.replaceStandardEscapes(StringUtil.replaceOctalEscapes(text))

        setupNodeLocation(new ConstantExpression(text, true), ctx)
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static Expression parseExpression(GroovyParser.NullExpressionContext ctx) {
        setupNodeLocation(new ConstantExpression(null), ctx)
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static Expression parseExpression(GroovyParser.AssignmentExpressionContext ctx) {
        def left = parseExpression(ctx.expression(0)) // TODO reference to AntlrParserPlugin line 2304 for error handling.
        def right = parseExpression(ctx.expression(1))
        setupNodeLocation(new BinaryExpression(left, createToken(ctx.getChild(1) as TerminalNode), right), ctx)
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static Expression parseExpression(GroovyParser.DeclarationExpressionContext ctx) {
        def left = new VariableExpression(ctx.IDENTIFIER().text)
        def col = ctx.start.charPositionInLine + 1 // FIXME Why assignment token location is it's first occurrence.
        def token = new Token(Types.ASSIGN, '=', ctx.start.line, col)
        def right = ctx.childCount == 2 ? new EmptyExpression() : parseExpression(ctx.expression())

        def expression = new DeclarationExpression(left, token, right)
        attachAnnotations(expression, ctx.annotationClause())
        setupNodeLocation(expression, ctx)
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static Expression parseExpression(GroovyParser.CallExpressionContext ctx) {

        def methodNode
        if (ctx.pathExpression()) {
            // Collect closure's in argumentList expression.
            def argumentListExpression = new ArgumentListExpression()
            ctx.closureExpressionRule().each { argumentListExpression.addExpression(parseExpression(it)) }

            //noinspection GroovyAssignabilityCheck
            def (Expression expression, String methodName, boolean implicitThis) = parsePathExpression(ctx.pathExpression())
            methodNode = new MethodCallExpression(expression, methodName, argumentListExpression)
            methodNode.implicitThis = implicitThis
        }
        else {
            def argumentListExpression = createArgumentList(ctx.argumentList())
            if (ctx.expression() instanceof GroovyParser.VariableExpressionContext) {
                methodNode = new MethodCallExpression(new VariableExpression("this"), new ConstantExpression(ctx.expression().text), argumentListExpression)
                methodNode.implicitThis = true
            }
            else {
                methodNode = new MethodCallExpression(parseExpression(ctx.expression()), new ConstantExpression("call"), argumentListExpression)
                methodNode.implicitThis = false
            }
        }
        methodNode
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    static MethodCallExpression parseExpression(GroovyParser.MethodCallExpressionContext ctx) {
        def method = new ConstantExpression(ctx.IDENTIFIER().text)
        ArgumentListExpression argumentListExpression = createArgumentList(ctx.argumentList())
        def expression = new MethodCallExpression(parseExpression(ctx.expression()), method, argumentListExpression)
        expression.implicitThis = false
        def op = ctx.getChild(1) as TerminalNode
        expression.spreadSafe = op.text == '*.'
        expression.safe = op.text == '?.'
        expression
    }

    static ClassNode parseExpression(GroovyParser.ClassNameExpressionContext ctx) {
        setupNodeLocation(ClassHelper.make(ctx.IDENTIFIER().join('.')), ctx)
    }

    static ClassNode parseExpression(GroovyParser.GenericClassNameExpressionContext ctx) {
        def classNode = parseExpression(ctx.classNameExpression())

        classNode.genericsTypes = parseGenericDeclaration(ctx.genericDeclarationList())
        setupNodeLocation(classNode, ctx)
    }

    static GenericsType[] parseGenericDeclaration(GroovyParser.GenericDeclarationListContext ctx) {
        ctx ? ctx.genericClassNameExpression().collect { setupNodeLocation(new GenericsType(parseExpression(it)), it) } : null
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // End of Expressions.
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    @SuppressWarnings("UnnecessaryQualifiedReference")
    private static ArgumentListExpression createArgumentList(GroovyParser.ArgumentListContext ctx) {
        def argumentListExpression = new ArgumentListExpression()
        ctx?.children?.each {
            if (it instanceof GroovyParser.ExpressionContext)
                argumentListExpression.addExpression(parseExpression(it))
            else if (it instanceof GroovyParser.ClosureExpressionRuleContext)
                argumentListExpression.addExpression(parseExpression(it))
        }
        argumentListExpression
    }

    static def attachAnnotations(AnnotatedNode node, List<GroovyParser.AnnotationClauseContext> ctxs) {
        for (ctx in ctxs) {
            def annotation = parseAnnotation(ctx)
            node.addAnnotation(annotation)
        }
    }

    static List<AnnotationNode> parseAnnotations(List<GroovyParser.AnnotationClauseContext> ctxs) {
        ctxs.collect { parseAnnotation(it) }
    }

    static AnnotationNode parseAnnotation(GroovyParser.AnnotationClauseContext ctx) {
        def node = new AnnotationNode(parseExpression(ctx.genericClassNameExpression()))
        if (ctx.annotationElement())
            node.addMember("value", parseAnnotationElement(ctx.annotationElement()))
        else {
            for (pair in ctx.annotationElementPair()) {
                node.addMember(pair.IDENTIFIER().text, parseAnnotationElement(pair.annotationElement()))
            }
        }

        setupNodeLocation(node, ctx)
    }

    static Expression parseAnnotationElement(GroovyParser.AnnotationElementContext ctx) {
        def annotationClause = ctx.annotationClause()
        if (annotationClause)
            setupNodeLocation(new AnnotationConstantExpression(parseAnnotation(annotationClause)), annotationClause)
        else
            parseExpression(ctx.expression())
    }


    static ClassNode[] parseThrowsClause(GroovyParser.ThrowsClauseContext ctx) {
        ctx ? ctx.classNameExpression().collect { parseExpression(it) } : []
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Utility methods.
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    static Token createToken(TerminalNode node) {
        def text = node.text
        new Token(node.text == '..<' || node.text == '..' ? Types.RANGE_OPERATOR : Types.lookup(text, Types.ANY),
            text, node.symbol.line, node.symbol.charPositionInLine + 1)
    }

    static ClassNode parseTypeDeclaration(GroovyParser.TypeDeclarationContext ctx) {
        !ctx || ctx.KW_DEF() ? ClassHelper.OBJECT_TYPE : setupNodeLocation(parseExpression(ctx.genericClassNameExpression()), ctx)
    }

    static Parameter[] parseParameters(GroovyParser.ArgumentDeclarationListContext ctx) {
        ctx.argumentDeclaration().collect {
            def parameter = new Parameter(parseTypeDeclaration(it.typeDeclaration()), it.IDENTIFIER().text)
            attachAnnotations(parameter, it.annotationClause())
            setupNodeLocation(parameter, it)
        }
    }

    static MethodNode getOrCreateClinitMethod(ClassNode classNode) {
        def methodNode = classNode.methods.find { it.name == "<clinit>" }
        if (!methodNode) {
            methodNode = new MethodNode("<clinit>", Opcodes.ACC_STATIC, ClassHelper.VOID_TYPE, [] as Parameter[], [] as ClassNode[], new BlockStatement())
            methodNode.synthetic = true
            classNode.addMethod(methodNode)
        }
        methodNode
    }

    /**
     * Sets location(lineNumber, colNumber, lastLineNumber, lastColumnNumber) for node using standard context information.
     * Note: this method is implemented to be closed over ASTNode. It returns same node as it received in arguments.
     * @param astNode Node to be modified.
     * @param ctx Context from which information is obtained.
     * @return Modified astNode.
     */
    static <T extends ASTNode> T setupNodeLocation(T astNode, ParserRuleContext ctx) {
        astNode.lineNumber = ctx.start.line
        astNode.columnNumber = ctx.start.charPositionInLine + 1
        astNode.lastLineNumber = ctx.stop.line
        astNode.lastColumnNumber = ctx.stop.charPositionInLine + 1 + ctx.stop.text.length()
        astNode
    }

    static <T extends ASTNode> T setupNodeLocation(T astNode, org.antlr.v4.runtime.Token token) {
        astNode.lineNumber = token.line
        astNode.columnNumber = token.charPositionInLine + 1
        astNode.lastLineNumber = token.line
        astNode.lastColumnNumber = token.charPositionInLine + 1 + token.text.length()
        astNode
    }

    int parseClassModifiers(@NotNull List<GroovyParser.ClassModifierContext> ctxs) {

        List<TerminalNode> visibilityModifiers = []
        int modifiers = 0
        for (child in ctxs.children) {
            if (child instanceof List) {
                assert child.size() == 1
                child = child[0]
            }
            assert child instanceof TerminalNode
            switch (child.symbol.type) {
                case GroovyLexer.VISIBILITY_MODIFIER: visibilityModifiers << child; break
                case GroovyLexer.KW_STATIC: modifiers |= checkModifierDuplication(modifiers, Opcodes.ACC_STATIC, child); break
                case GroovyLexer.KW_ABSTRACT: modifiers |= checkModifierDuplication(modifiers, Opcodes.ACC_ABSTRACT, child); break
                case GroovyLexer.KW_FINAL: modifiers |= checkModifierDuplication(modifiers, Opcodes.ACC_FINAL, child); break
                case GroovyLexer.KW_STRICTFP: modifiers |= checkModifierDuplication(modifiers, Opcodes.ACC_STRICT, child); break
            }
        }
        if (visibilityModifiers)
            modifiers |= parseVisibilityModifiers(visibilityModifiers, 0) // Here we shouldn't pass any default value. Old code. Needs refactoring.
        else
            modifiers |= Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC
        modifiers
    }

    int checkModifierDuplication(int modifier, int opcode, TerminalNode node) {
        if (!(modifier & opcode))
            modifier | opcode
        else {
            def symbol = node.symbol

            def line = symbol.line
            def col = symbol.charPositionInLine + 1
            sourceUnit.addError(new SyntaxException("Cannot repeat modifier: $symbol.text at line: $line column: $col. File: $sourceUnit.name", line, col))
            modifier
        }
    }

    int parseModifier(Collection<TerminalNode> nodes, int opcode) {
        if (!nodes)
            return 0

        if (nodes.size() > 1) {
            def modifier = nodes[1].symbol

            def line = modifier.line
            def col = modifier.charPositionInLine + 1
            sourceUnit.addError(new SyntaxException("Cannot repeat modifier: $modifier.text at line: $line column: $col. File: $sourceUnit.name", line, col))
        }
        opcode
    }

    /**
     * Traverse through modifiers, and combine them in one int value. Raise an error if there is multiple occurrences of same modifier.
     * @param ctxList modifiers list.
     * @param defaultVisibilityModifier Default visibility modifier. Can be null. Applied if providen, and no visibility modifier exists in the ctxList.
     * @return tuple of int modifier and boolean flag, signalising visibility modifiers presence(true if there is visibility modifier in list, false otherwise).
     * @see #checkModifierDuplication(int, int, org.antlr.v4.runtime.tree.TerminalNode)
     */
    def parseModifiers(List<GroovyParser.MemberModifierContext> ctxList, Integer defaultVisibilityModifier = null) {
        int modifiers = 0;
        boolean hasVisibilityModifier = false;
        ctxList.each {
            def child = (it.getChild(0) as TerminalNode)
            switch (child.symbol.type) {
                case GroovyLexer.KW_STATIC: modifiers |= checkModifierDuplication(modifiers, Opcodes.ACC_STATIC, child); break
                case GroovyLexer.KW_ABSTRACT: modifiers |= checkModifierDuplication(modifiers, Opcodes.ACC_ABSTRACT, child); break
                case GroovyLexer.KW_FINAL: modifiers |= checkModifierDuplication(modifiers, Opcodes.ACC_FINAL, child); break
                case GroovyLexer.KW_NATIVE: modifiers |= checkModifierDuplication(modifiers, Opcodes.ACC_NATIVE, child); break
                case GroovyLexer.KW_SYNCHRONIZED: modifiers |= checkModifierDuplication(modifiers, Opcodes.ACC_SYNCHRONIZED, child); break
                case GroovyLexer.KW_TRANSIENT: modifiers |= checkModifierDuplication(modifiers, Opcodes.ACC_TRANSIENT, child); break
                case GroovyLexer.KW_VOLATILE: modifiers |= checkModifierDuplication(modifiers, Opcodes.ACC_VOLATILE, child); break
                case GroovyLexer.VISIBILITY_MODIFIER:
                    modifiers |= parseVisibilityModifiers(child)
                    hasVisibilityModifier = true
                    break
            }
        }
        if (!hasVisibilityModifier && defaultVisibilityModifier != null)
            modifiers |= defaultVisibilityModifier

        [modifiers, hasVisibilityModifier]
    }

    void reportError(String text, int line, int col) {
        sourceUnit.addError(new SyntaxException(text, line, col))
    }

    static int parseVisibilityModifiers(TerminalNode modifier) {
        assert modifier.symbol.type == GroovyLexer.VISIBILITY_MODIFIER
        switch (modifier.symbol.text) {
            case "public": Opcodes.ACC_PUBLIC; break
            case "private": Opcodes.ACC_PRIVATE; break
            case "protected": Opcodes.ACC_PROTECTED; break
            default: throw new AssertionError("$modifier.symbol.text is not a valid visibility modifier!")
        }
    }

    int parseVisibilityModifiers(List<TerminalNode> modifiers, int defaultValue) {
        if (!modifiers)
            return defaultValue

        if (modifiers.size() > 1) {
            def modifier = modifiers[1].symbol

            def line = modifier.line
            def col = modifier.charPositionInLine + 1

            reportError("Cannot specify modifier: $modifier.text when access scope has already been defined at line: $line column: $col. File: $sourceUnit.name", line, col)
        }

        parseVisibilityModifiers(modifiers[0])
    }

    /**
     * Method for construct string from string literal handling empty strings.
     * @param node
     * @return
     */
    static String parseString(TerminalNode node) {
        def t = node.text
        t ? t[1..-2] : t
    }
}
