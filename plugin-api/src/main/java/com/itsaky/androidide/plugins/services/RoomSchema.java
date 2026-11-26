package com.itsaky.androidide.plugins.services;

import androidx.annotation.NonNull;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One schema file Room exported into the open project ({@code exportSchema} on): {@code <schemaLocation>/<class>/<version>.json}.
 * Immutable.
 *
 * <p>
 * The entities and views keep Room's own JSON keys, so a template reads them as Room writes them: an entity has
 * {@code tableName}, {@code createSql} (with {@code ${TABLE_NAME}} for the table name), {@code fields} (each with
 * {@code fieldPath}, {@code columnName}, {@code affinity}, {@code notNull}), {@code primaryKey} ({@code autoGenerate},
 * {@code columnNames}), {@code indices} and {@code foreignKeys}; a view has {@code viewName} and {@code createSql}. Room
 * leaves out {@code indices}, {@code foreignKeys} and {@code views} when they are empty; here they are always present,
 * as empty lists. JSON numbers are {@link Long} or {@link Double}, objects {@link Map} and arrays {@link List}, none of
 * them modifiable.
 */
public final class RoomSchema {

	private final String className;
	private final int version;
	private final String identityHash;
	private final String path;
	private final List<Map<String, Object>> entities;
	private final List<Map<String, Object>> views;

	/**
	 * Creates a schema. The lists are kept as given, so pass unmodifiable ones.
	 *
	 * @param className
	 *            the database class, from the folder name (Room does not write it into the JSON)
	 * @param version
	 *            {@code database.version}
	 * @param identityHash
	 *            {@code database.identityHash}
	 * @param path
	 *            the file, relative to the project root, with {@code /} separators
	 * @param entities
	 *            {@code database.entities}
	 * @param views
	 *            {@code database.views}, empty when the file has none
	 */
	public RoomSchema(@NonNull String className, int version, @NonNull String identityHash, @NonNull String path, @NonNull List<Map<String, Object>> entities, @NonNull List<Map<String, Object>> views) {
		this.className = Objects.requireNonNull(className, "className");
		this.version = version;
		this.identityHash = Objects.requireNonNull(identityHash, "identityHash");
		this.path = Objects.requireNonNull(path, "path");
		this.entities = Collections.unmodifiableList(Objects.requireNonNull(entities, "entities"));
		this.views = Collections.unmodifiableList(Objects.requireNonNull(views, "views"));
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof RoomSchema)) {
			return false;
		}
		final RoomSchema that = (RoomSchema) other;
		return version == that.version && className.equals(that.className) && identityHash.equals(that.identityHash) && path.equals(that.path) && entities.equals(that.entities) && views.equals(that.views);
	}

	/**
	 * Gets the database class, such as {@code com.example.AppDatabase}.
	 *
	 * @return the class name, from the folder that holds the file (never null)
	 */
	@NonNull
	public String getClassName() {
		return className;
	}

	/**
	 * Gets the entities.
	 *
	 * @return {@code database.entities}, each with {@code indices} and {@code foreignKeys} present (never null)
	 */
	@NonNull
	public List<Map<String, Object>> getEntities() {
		return entities;
	}

	/**
	 * Gets the identity hash Room keeps in {@code room_master_table} of a database created with this schema.
	 *
	 * @return {@code database.identityHash} (never null)
	 */
	@NonNull
	public String getIdentityHash() {
		return identityHash;
	}

	/**
	 * Gets where the file is.
	 *
	 * @return the path relative to the project root, with {@code /} separators (never null)
	 */
	@NonNull
	public String getPath() {
		return path;
	}

	/**
	 * Gets the schema version, which a database created with this schema has as its {@code user_version}.
	 *
	 * @return {@code database.version}
	 */
	public int getVersion() {
		return version;
	}

	/**
	 * Gets the views.
	 *
	 * @return {@code database.views}, empty when the file has none (never null)
	 */
	@NonNull
	public List<Map<String, Object>> getViews() {
		return views;
	}

	@Override
	public int hashCode() {
		return Objects.hash(className, version, identityHash, path);
	}

	@Override
	public String toString() {
		return "RoomSchema{" + className + " v" + version + ", " + path + "}";
	}
}
