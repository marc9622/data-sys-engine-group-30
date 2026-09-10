grammar Sql;
options { caseInsensitive = true; } // ANTLR ≥ 4.10
script : (statement ';')+ EOF ;
statement : createTable | copy | select ;
createTable : CREATE TABLE IDENTIFIER '(' columnDef ('
,
columnDef : IDENTIFIER columnType ;
columnType : STRING | LONG | DOUBLE ;
' columnDef)* ')' ;
copy : COPY IDENTIFIER FROM STRING
_
LITERAL ;
select : SELECT '*' FROM IDENTIFIER (WHERE predicate)? ;
predicate : IDENTIFIER comparison=('
=
' | '<' | '>') literal ;
literal : STRING
_
LITERAL | LONG
_
LITERAL | DOUBLE
_
LITERAL ;
// Lexer. Keyword rules MUST precede IDENTIFIER, or IDENTIFIER swallows them.
CREATE : 'CREATE' ; TABLE : 'TABLE' ; COPY : 'COPY' ; FROM : 'FROM' ;
SELECT : 'SELECT' ; WHERE : 'WHERE' ;
STRING : 'STRING' ; LONG : 'LONG' ; DOUBLE : 'DOUBLE' ;
IDENTIFIER : [A-Z
_] [A-Z
_
0-9]* ; // caseInsensitive covers a–z
LONG
LITERAL : '
-
_
'? [0-9]+ ;
DOUBLE
LITERAL : '
-
_
'? [0-9]+ '
.
' [0-9]+ ;
STRING
LITERAL : '\''
_
~['\r\n]* '\'' ;
LINE
COMMENT : '
'
--
_
~[\r\n]* -> skip ;
WS : [ \t\r\n]+ -> skip ;