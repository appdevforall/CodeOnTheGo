package moe.shizuku.server;

interface IShizukuApplication {
    oneway void bindApplication(in Bundle data) = 1;
}