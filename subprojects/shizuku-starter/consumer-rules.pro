# User-service processes are started by class name through app_process
# (ServiceStarter.commandForUserService), so R8 sees no caller of main.
-keep class moe.shizuku.starter.ServiceStarter { public static void main(java.lang.String[]); }
