package org.concordia.babycdf;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import java.nio.file.*;import static org.junit.jupiter.api.Assertions.*;
class RetiredSourceEntrypointsTest {
 @TempDir Path root;
 @Test void publicSourceStepsAndOptionalsRefuseBeforeAnyCommand()throws Exception{
  String runner=Files.readString(Path.of("../demo/run-demo.sh"));for(String name:new String[]{"run_step","run_opt_step"}){
   int start=runner.indexOf(name+"() {");String function=runner.substring(start,runner.indexOf("\n}",start)+2);Path harness=root.resolve(name+".sh");Files.writeString(harness,"set -eu\n"+function+"\nRAW_DIR=''\nSTATE='"+root+"'\nHERE=x\nbabycdf_build_root(){ echo MUTATION; exit 99; }\ncompose(){ echo MUTATION; exit 99; }\n"+name+" \"$1\"\n");
   for(String n:name.equals("run_step")?new String[]{"3","4","5"}:new String[]{"2","3","4","5"}){var process=new ProcessBuilder("sh",harness.toString(),n).redirectErrorStream(true).start();String output=new String(process.getInputStream().readAllBytes());assertEquals(2,process.waitFor());assertFalse(output.contains("MUTATION"));assertTrue(output.contains("Nothing was run"));}
  }
 }
}
