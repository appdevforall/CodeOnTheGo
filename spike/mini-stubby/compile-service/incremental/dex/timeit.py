import subprocess, time, sys, os, glob
JAVA=os.environ["JAVA_HOME"]+"/bin/java"
D8JAR=sys.argv[1]; AJAR=sys.argv[2]; SP=sys.argv[3]
def run(args):
    t=time.perf_counter()
    r=subprocess.run([JAVA,"-cp",D8JAR,"com.android.tools.r8.D8"]+args,
                     capture_output=True,text=True)
    dt=(time.perf_counter()-t)*1000
    if r.returncode!=0: 
        sys.stderr.write(r.stderr[-500:]); 
    return dt, r.returncode
def best(args,n=3):
    xs=[]; 
    for _ in range(n):
        dt,rc=run(args)
        if rc!=0: return None
        xs.append(dt)
    return min(xs)
# baseline: JVM start + dex a single trivial class
print("size,classes,whole_app_ms,single_class_ms,dex_bytes")
for label,nf in [("600",20),("3000",50),("15000",100),("30000",200)]:
    outc=os.path.join(SP,f"w{label}","out-inc")
    classes=glob.glob(os.path.join(outc,"**","*.class"),recursive=True)
    wd=os.path.join(SP,f"dexout{label}"); os.makedirs(wd,exist_ok=True)
    whole=best(["--release","--min-api","30","--lib",AJAR,"--output",wd]+classes)
    dexb=os.path.getsize(os.path.join(wd,"classes.dex")) if os.path.exists(os.path.join(wd,"classes.dex")) else -1
    # single-class incremental: dex just one leaf, classpath = the rest
    one=[c for c in classes if c.endswith("Leaf0.class")][:1] or classes[:1]
    sd=os.path.join(SP,f"single{label}"); os.makedirs(sd,exist_ok=True)
    single=best(["--release","--min-api","30","--lib",AJAR,"--classpath",outc,
                 "--output",sd,"--intermediate"]+one)
    print(f"{label},{len(classes)},{whole:.0f},{single:.0f},{dexb}")
