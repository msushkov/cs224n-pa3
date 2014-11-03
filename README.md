# Run from cs224n-pa3/java directory:
java -Xmx500m -cp "extlib/*:classes" cs224n.assignments.CoreferenceTester -path ../data -model baseline -data dev -documents 100

java -Xmx500m -cp "extlib/*:classes" cs224n.assignments.CoreferenceTester -path ../data -model BetterBaseline -data dev -documents 100