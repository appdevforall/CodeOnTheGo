import psycopg
import os
import argparse

# uv run --with psycopg[binary] insert.py build_name start end size

def insert_ci_perf(db_name, user, host, port, apk_name, start_time, end_time, apk_size_mb):
    """
    Connects to a PostgreSQL database using psycopg and inserts a new row
    into the 'ci_perf' table.

    Args:
        db_name (str): The name of the database.
        user (str): The username for the database connection.
        host (str): The database host address.
        port (str): The port number for the database connection.
        apk_name (str): The name of the APK.
        start_time (int): The build start time in seconds since the epoch.
        end_time (int): The build end time in seconds since the epoch.
        apk_size_mb (float): The size of the APK in megabytes.
    """
    conn = None
    try:
        # Get the password from the environment variable
        password = os.environ.get("DB_PASSWORD")
        if not password:
            raise ValueError("DB_PASSWORD environment variable not set.")

        # Calculate the build time in seconds
        build_time_secs = int(end_time) - int(start_time)

        # Establish a connection to the PostgreSQL database
        with psycopg.connect(
            dbname=db_name,
            user=user,
            password=password,
            host=host,
            port=port
        ) as conn:
            with conn.cursor() as cur:
                # Use a parameterized query to prevent SQL injection attacks.
                # psycopg uses %s as a placeholder for arguments.
                cur.execute("""
                    INSERT INTO ci_perf (apk_name, build_time_secs, apk_size_mb)
                    VALUES (%s, %s, %s);
                """, (apk_name, build_time_secs, apk_size_mb))

            # Commit the transaction to save the changes to the database
            conn.commit()
            print(f"Successfully inserted a new record for '{apk_name}'. Build time: {build_time_secs}s.")

    except (ValueError, Exception, psycopg.DatabaseError) as error:
        print(f"Error: {error}")
    finally:
        if conn is not None:
            conn.close()

if __name__ == "__main__":
    # Set up argument parsing
    parser = argparse.ArgumentParser(description="Insert build performance data into the ci_perf table.")
    parser.add_argument("apk_name", type=str, help="The name of the APK.")
    parser.add_argument("start_time", type=int, help="The build start time (seconds since epoch).")
    parser.add_argument("end_time", type=int, help="The build end time (seconds since epoch).")
    parser.add_argument("apk_size_mb", type=float, help="The size of the APK in megabytes.")
    
    args = parser.parse_args()

    # Call the function with the parsed arguments
    insert_ci_perf(
        db_name="postgres",  # Or your specific database name
        user="postgres",
        host="database-1.cd6y8y8skce9.us-west-2.rds.amazonaws.com",
        port="5432",
        apk_name=args.apk_name,
        start_time=args.start_time,
        end_time=args.end_time,
        apk_size_mb=args.apk_size_mb
    )
