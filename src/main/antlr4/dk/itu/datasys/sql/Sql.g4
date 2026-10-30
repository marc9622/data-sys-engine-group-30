grammar Sql;

options { caseInsensitive = true; }   // ANTLR ≥ 4.10

script      : (statement ';')+ EOF ;
statement   : createTable | copy | select | delete ; 

createTable : CREATE TABLE IDENTIFIER '(' columnDef (',' columnDef)* ')' ;
columnDef   : IDENTIFIER columnType ;
columnType  : STRING | LONG | DOUBLE ;

copy        : COPY IDENTIFIER FROM STRING_LITERAL ;

select      : SELECT ('*' | columnList) FROM IDENTIFIER (WHERE predicate)? (LIMIT limit)? ;
delete      : DELETE ('*' | columnList) FROM IDENTIFIER (WHERE predicate)? ;
columnList  : IDENTIFIER (',' IDENTIFIER)* ;
predicate   : IDENTIFIER comparison=('=' | '<' | '>') literal ;
limit       : LONG_LITERAL ;
literal     : STRING_LITERAL | LONG_LITERAL | DOUBLE_LITERAL ;

// Lexer. Keyword rules MUST precede IDENTIFIER, or IDENTIFIER swallows them.
CREATE : 'CREATE' ;   TABLE : 'TABLE' ;   COPY : 'COPY' ;   FROM : 'FROM' ;
SELECT : 'SELECT' ;   WHERE : 'WHERE' ;   LIMIT : 'LIMIT' ;
STRING : 'STRING' ;   LONG : 'LONG' ;   DOUBLE : 'DOUBLE' ;
DELETE : 'DELETE' ;   WHERE : 'WHERE' ;  

IDENTIFIER      : [A-Z_] [A-Z_0-9]* ;        // caseInsensitive covers a–z
LONG_LITERAL    : '-'? [0-9]+ ;
DOUBLE_LITERAL  : '-'? [0-9]+ '.' [0-9]+ ;
STRING_LITERAL  : '\'' ~['\r\n]* '\'' ;
LINE_COMMENT    : '--' ~[\r\n]* -> skip ;
WS              : [ \t\r\n]+ -> skip ;
