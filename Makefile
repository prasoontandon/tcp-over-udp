build:
	javac src/*.java

run: build
	java -classpath src/ TCPend -p 1234 -m 1234 -c 1234 -f tmpFile &
	java -classpath src/ TCPend -p 1234 -s 1234 -a 1234 -f tmpFile -m 1234 -c 1234

tests: build
	java -classpath src/ TCPTests

rec:
	java -classpath src/ TCPend -p 5000 -m 3 -c 3 -f outputFile.txt

sen:
	java -classpath src/ TCPend -p 8888 -s 2130706433 -a 5000 -f testFile.txt -m 3 -c 3

clean:
	rm -rf src/*.class