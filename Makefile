build:
	javac src/*.java

run: build
	java -classpath src/ TCPend -p 1234 -m 1234 -c 1234 -f tmpFile &
	java -classpath src/ TCPend -p 1234 -s 1234 -a 1234 -f tmpFile -m 1234 -c 1234

tests: build
	java -classpath src/ TCPTests

rec:
	java -classpath src/ TCPend -p 5000 -m 10 -c 2 -f outputFile.txt

sen:
	java -classpath src/ TCPend -p 8888 -s localhost -a 5000 -f tFiles/large.txt -m 10 -c 2

clean:
	rm -rf src/*.class