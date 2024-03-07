# bugswarm-javaparser

## To generate CFG:
``` bash
mvn clean compile 
mvn exec:java -Dexec.mainClass="com.example.CFGProcessor"
```

## To generate the PNG from the DOT file:
`dot -Tpng cfg.dot -o cfg.png`
