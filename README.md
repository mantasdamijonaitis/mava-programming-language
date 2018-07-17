# MAVA Programming language

## Get up and running

Download Maven from here:

https://maven.apache.org/download.cgi

Please download Binary zip archive.

Then, If you have Windows OS, add Maven bin folder to environment Path variable.

Go to the project folder and execute:

```bash
mvn -q antlr4:antlr4 install exec:java -Dexec.args="test.mava"
```

Where test.mava is your MAVA application in current folder.

Made by:

**Mantas Damijonaitis IFF-5/4**
**Mantas Kleiva IFF-5/4**