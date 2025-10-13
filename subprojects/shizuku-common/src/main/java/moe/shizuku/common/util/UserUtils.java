package moe.shizuku.common.util;

import android.content.pm.UserInfo;
import androidx.annotation.NonNull;
import java.util.Collections;
import java.util.List;
import rikka.hidden.compat.UserManagerApis;

/**
 * @author Akash Yadav
 */
public class UserUtils {

	public static final int USER_PRIMARY = 0;

	@NonNull
	public static List<Integer> getUserIds() {
		// Limit interaction to the primary user
		return Collections.singletonList(USER_PRIMARY);
	}

	@NonNull
	public static UserInfo getUserInfo(int userId) {
		if (userId != USER_PRIMARY) {
			throw new UnsupportedOperationException("Interaction with non-primary users is restricted!");
		}

		return UserManagerApis.getUserInfo(userId);
	}
}
