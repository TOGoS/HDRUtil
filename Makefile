.PHONY: clean default

default: HDRUtil.jar

HDRUtil.jar: $(shell find src)
	rm -rf bin
	find src -name *.java >.src.lst
	mkdir -p bin
	javac -source 1.6 -target 1.6 -sourcepath src -d bin @.src.lst
	jar -ce togos.hdrutil.AdjusterUI -C bin . >HDRUtil.jar

HDRUtil.jar.urn: HDRUtil.jar
	ccouch id "$<" >"$@"

clean:
	rm -rf bin HDRUtil.jar .src.lst
