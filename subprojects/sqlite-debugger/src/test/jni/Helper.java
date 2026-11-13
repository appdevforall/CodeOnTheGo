/** Opens and uses "app" connections with the same libsqlite3x.so the debugger runs in. */
public class Helper {
	static native long open(String path);

	static native int exec(long db, String sql);

	static native int close(long db);
}
