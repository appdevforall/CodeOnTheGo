import com.android.tools.r8.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * In-process D8 dexing benchmark (warm — one JVM, D8.run() called repeatedly, so
 * JVM/D8 startup is paid once, exactly like the warm compile service). Two axes:
 *   A) whole-app dex vs app size (600/3k/15k/30k LoC synthetic apps)
 *   B) incremental dex vs #classes(=~methods) changed, within the 30k app
 * Args: <androidJar> <baseDir containing w600/w3000/w15000/w30000/out-inc>
 */
public class OnDeviceDexBench {
  static long now(){ return System.nanoTime()/1_000_000; }
  static void wipe(Path p) throws Exception {
    if (Files.exists(p)) Files.walk(p).sorted(Comparator.reverseOrder())
        .forEach(x->{try{Files.delete(x);}catch(Exception e){}});
    Files.createDirectories(p);
  }
  static long dexOnce(List<Path> program, Path classpathDir, Path lib, Path out) throws Exception {
    wipe(out);
    long t=now();
    D8Command.Builder b = D8Command.builder()
      .addProgramFiles(program)
      .addLibraryFiles(lib)
      .setMinApiLevel(30)
      .setMode(CompilationMode.RELEASE)
      .setOutput(out, OutputMode.DexIndexed);
    if (classpathDir!=null) b.addClasspathFiles(classpathDir);
    D8.run(b.build());
    return now()-t;
  }
  static long best(int n, List<Path> program, Path cp, Path lib, Path out) throws Exception {
    long m=Long.MAX_VALUE;
    for(int i=0;i<n;i++) m=Math.min(m, dexOnce(program, cp, lib, out));
    return m;
  }
  static List<Path> classesOf(Path dir) throws Exception {
    return Files.walk(dir).filter(p->p.toString().endsWith(".class"))
      .sorted().collect(Collectors.toList());
  }
  public static void main(String[] a) throws Exception {
    Path lib = Path.of(a[0]);
    Path base = Path.of(a[1]);
    Path out = base.resolve("dexout");
    // approx methods/class by app (lines/6): 600->30/6=5, 3k->60/6=10, 15k->150/6=25, 30k->25
    int[] sizes = {600,3000,15000,30000};
    int[] mpc   = {5,10,25,25};

    // warm-up: dex a single class cold, then warm — report both to show startup cost
    List<Path> c30 = classesOf(base.resolve("w30000/out-inc"));
    long cold = dexOnce(c30.subList(0,1), base.resolve("w30000/out-inc"), lib, out);
    long warm1 = dexOnce(c30.subList(0,1), base.resolve("w30000/out-inc"), lib, out);
    System.out.println("STARTUP cold 1-class dex="+cold+"ms  warm 1-class dex="+warm1+"ms (delta=JVM/D8 startup="+(cold-warm1)+"ms)");

    System.out.println("\n== AXIS A: whole-app dex vs app size (warm, best-of-3) ==");
    System.out.println("size_LoC,classes,~methods,whole_app_dex_ms");
    for(int i=0;i<sizes.length;i++){
      List<Path> cs = classesOf(base.resolve("w"+sizes[i]+"/out-inc"));
      long ms = best(3, cs, base.resolve("w"+sizes[i]+"/out-inc"), lib, out);
      System.out.println(sizes[i]+","+cs.size()+","+(cs.size()*mpc[i])+","+ms);
    }

    System.out.println("\n== AXIS B: incremental dex vs #classes changed (in 30k app, warm, best-of-3) ==");
    System.out.println("classes_dexed,~methods,dex_ms");
    int[] ns = {1,5,20,50,100,200};
    for(int n: ns){
      if(n>c30.size()) n=c30.size();
      List<Path> sub = c30.subList(0, n);
      long ms = best(3, sub, base.resolve("w30000/out-inc"), lib, out);
      System.out.println(n+","+(n*25)+","+ms);
    }

    System.out.println("\n== CROSS-CHECK: dex 5 classes — does app size matter? (warm, best-of-3) ==");
    System.out.println("app_size,classes_dexed,dex_ms");
    for(int s: new int[]{3000,30000}){
      List<Path> cs = classesOf(base.resolve("w"+s+"/out-inc"));
      long ms = best(3, cs.subList(0,Math.min(5,cs.size())), base.resolve("w"+s+"/out-inc"), lib, out);
      System.out.println(s+",5,"+ms);
    }
  }
}
