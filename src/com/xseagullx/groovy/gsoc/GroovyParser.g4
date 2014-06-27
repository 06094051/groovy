
parser grammar GroovyParser;

options { tokenVocab = GroovyLexer; }

@parser::members {
    String currentClassName = null; // Used for correct constructor recognition.
}

compilationUnit: (NL*) packageDefinition? (NL | SEMICOLON)* (importStatement | NL)* (NL | SEMICOLON)* (classDeclaration | NL)* EOF;

packageDefinition:
    (annotationClause (NL | annotationClause)*)? KW_PACKAGE (IDENTIFIER (DOT IDENTIFIER)*);
importStatement:
    (annotationClause (NL | annotationClause)*)? KW_IMPORT (IDENTIFIER (DOT IDENTIFIER)* (DOT MULT)?);
classDeclaration:
    ((annotationClause | classModifier) (NL | annotationClause | classModifier)*)? KW_CLASS IDENTIFIER { currentClassName = $IDENTIFIER.text; } genericDeclarationList? (KW_EXTENDS genericClassNameExpression)? (KW_IMPLEMENTS genericClassNameExpression (COMMA genericClassNameExpression)*)? (NL)* LCURVE (classMember | NL | SEMICOLON)* RCURVE ;
classMember:
    constructorDeclaration | methodDeclaration | fieldDeclaration | objectInitializer | classInitializer;

// Members // FIXME Make more strict check for def keyword. It can't repeat.
methodDeclaration:
    (
        (memberModifier | annotationClause | KW_DEF) (memberModifier | annotationClause | KW_DEF | NL)* (
            (genericDeclarationList genericClassNameExpression) | typeDeclaration
        )?
    |
        genericClassNameExpression
    )
    IDENTIFIER LPAREN argumentDeclarationList RPAREN throwsClause? LCURVE blockStatement? RCURVE
;

fieldDeclaration:
    (memberModifier | annotationClause | KW_DEF) (memberModifier | annotationClause | KW_DEF | NL)* genericClassNameExpression? IDENTIFIER
    | genericClassNameExpression IDENTIFIER
;
constructorDeclaration: { _input.LT(_input.LT(1).getType() == VISIBILITY_MODIFIER ? 2 : 1).getText().equals(currentClassName) }?
    VISIBILITY_MODIFIER? IDENTIFIER LPAREN argumentDeclarationList RPAREN throwsClause? LCURVE blockStatement? RCURVE ; // Inner NL 's handling.
objectInitializer: LCURVE blockStatement? RCURVE ;
classInitializer: KW_STATIC LCURVE blockStatement? RCURVE ;

typeDeclaration:
    (genericClassNameExpression | KW_DEF)
;

annotationClause: //FIXME handle assignment expression.
    AT genericClassNameExpression ( LPAREN ((annotationElementPair (COMMA annotationElementPair)*) | annotationElement)? RPAREN )?
;
annotationElementPair: IDENTIFIER ASSIGN annotationElement ;
annotationElement: expression | annotationClause ;

genericDeclarationList:
    LT genericClassNameExpression (COMMA genericClassNameExpression)* GT
;

throwsClause: KW_THROWS classNameExpression (COMMA classNameExpression)*;

argumentDeclarationList:
    argumentDeclaration (COMMA argumentDeclaration)* | /* EMPTY ARGUMENT LIST */ ;
argumentDeclaration:
    annotationClause* typeDeclaration? IDENTIFIER ;

blockStatement: (statement | NL)+ ;

statement:
    cmdExpressionRule #commandExpressionStatement
    | expression #expressionStatement
    | KW_FOR LPAREN (expression)? SEMICOLON expression? SEMICOLON expression? RPAREN LCURVE (statement | SEMICOLON | NL)* RCURVE #classicForStatement
    | KW_FOR LPAREN typeDeclaration? IDENTIFIER KW_IN expression RPAREN LCURVE (statement | SEMICOLON | NL)* RCURVE #forInStatement
    | KW_IF LPAREN expression RPAREN LCURVE (statement | SEMICOLON | NL)*  RCURVE (KW_ELSE LCURVE (statement | SEMICOLON | NL)* RCURVE)? #ifStatement
    | KW_WHILE LPAREN expression RPAREN LCURVE (statement | SEMICOLON | NL)*  RCURVE  #whileStatement
    | KW_SWITCH LPAREN expression RPAREN LCURVE
        (
          (caseStatement | NL)*
          (KW_DEFAULT COLON (statement | SEMICOLON | NL)*)?
        )
      RCURVE #switchStatement
    |  tryBlock ((catchBlock+ finallyBlock?) | finallyBlock) #tryCatchFinallyStatement
    | (KW_CONTINUE | KW_BREAK) #controlStatement
    | KW_RETURN expression? #returnStatement
    | KW_THROW expression #throwStatement
;

tryBlock: KW_TRY LCURVE blockStatement? RCURVE NL*;
catchBlock: KW_CATCH LPAREN ((classNameExpression (BOR classNameExpression)* IDENTIFIER) | IDENTIFIER) RPAREN LCURVE blockStatement? RCURVE NL*;
finallyBlock: KW_FINALLY LCURVE blockStatement? RCURVE;

caseStatement: (KW_CASE expression COLON (statement | SEMICOLON | NL)* );

cmdExpressionRule: pathExpression ( argumentList IDENTIFIER)* argumentList IDENTIFIER? ;
pathExpression: (IDENTIFIER DOT)* IDENTIFIER ;

closureExpressionRule: LCURVE argumentDeclarationList CLOSURE_ARG_SEPARATOR blockStatement? RCURVE ;

expression:
    closureExpressionRule #closureExpression
    | LBRACK (expression (COMMA expression)*)?RBRACK #listConstructor
    | LBRACK (COLON | (mapEntry (COMMA mapEntry)*) )RBRACK #mapConstructor
    | expression (DOT | SAFE_DOT | STAR_DOT) IDENTIFIER LPAREN argumentList? RPAREN #methodCallExpression
    | expression (DOT | SAFE_DOT | STAR_DOT | ATTR_DOT) IDENTIFIER #fieldAccessExpression
    | expression LPAREN argumentList? RPAREN #callExpression
    | pathExpression closureExpressionRule+ #callExpression
    | LPAREN expression RPAREN #parenthesisExpression
    | expression (DECREMENT | INCREMENT)  #postfixExpression
    | (NOT | BNOT) expression #unaryExpression
//  | (PLUS | MINUS) expression #unaryExpression // FIXME: return unary minus and plus expressions.
    | (DECREMENT | INCREMENT) expression #prefixExpression
    | expression POWER expression #binaryExpression
    | expression (MULT | DIV | MOD) expression #binaryExpression
    | expression (PLUS | MINUS) expression #binaryExpression
    | expression (LSHIFT | RSHIFT | RUSHIFT | RANGE | ORANGE) expression #binaryExpression
    | expression (((LT | LTE | GT | GTE | KW_IN) expression) | ((KW_AS | KW_INSTANCEOF) genericClassNameExpression)) #binaryExpression
    | expression (EQUAL | UNEQUAL | SPACESHIP) expression #binaryExpression
    | expression (FIND | MATCH) expression #binaryExpression
    | expression BAND expression #binaryExpression
    |<assoc=right> expression XOR expression #binaryExpression
    | expression BOR expression #binaryExpression
    | expression AND expression #binaryExpression
    | expression OR expression #binaryExpression
    | expression (ASSIGN | PLUS_ASSIGN | MINUS_ASSIGN | MULT_ASSIGN | DIV_ASSIGN | MOD_ASSIGN | BAND_ASSIGN | XOR_ASSIGN | BOR_ASSIGN | LSHIFT_ASSIGN | RSHIFT_ASSIGN | RUSHIFT_ASSIGN) expression #assignmentExpression
    | STRING #constantExpression
    | DECIMAL #constantDecimalExpression
    | INTEGER #constantIntegerExpression
    | KW_NULL #nullExpression
    | (KW_TRUE | KW_FALSE) #boolExpression
    | IDENTIFIER #variableExpression
    | annotationClause* typeDeclaration IDENTIFIER (ASSIGN expression)? #declarationExpression
;

classNameExpression: IDENTIFIER (DOT IDENTIFIER)* ;

genericClassNameExpression: classNameExpression genericDeclarationList? ;

mapEntry:
    STRING COLON expression
    | IDENTIFIER COLON expression
    | LPAREN expression RPAREN COLON expression
;

classModifier: //JSL7 8.1 FIXME Now gramar allows modifier duplication. It's possible to make it more strict listing all 24 permutations.
VISIBILITY_MODIFIER | KW_STATIC | (KW_ABSTRACT | KW_FINAL) | KW_STRICTFP ;

memberModifier:
    VISIBILITY_MODIFIER | KW_STATIC | (KW_ABSTRACT | KW_FINAL) | KW_NATIVE | KW_SYNCHRONIZED | KW_TRANSIENT | KW_VOLATILE ;

argumentList: ( (closureExpressionRule)+ | expression (COMMA expression)*) ;
