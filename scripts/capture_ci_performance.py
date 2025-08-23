#!/usr/bin/env python3
"""
Script to capture CI pipeline performance metrics.
Takes build start time, build end time, and APK path as arguments.
Reads database connection details from environment variables.
Writes performance data to ci_data table.
"""

import argparse
import os
import sys
from datetime import datetime
import psycopg
from psycopg import sql


def parse_arguments():
    """Parse command line arguments."""
    parser = argparse.ArgumentParser(
        description="Capture CI pipeline performance metrics"
    )
    parser.add_argument(
        "build_start_time",
        type=int,
        help="Build start time in seconds since epoch"
    )
    parser.add_argument(
        "build_end_time", 
        type=int,
        help="Build end time in seconds since epoch"
    )
    parser.add_argument(
        "apk_path",
        type=str,
        help="Path to the generated APK file"
    )
    
    return parser.parse_args()


def get_database_config():
    """Get database configuration from environment variables."""
    db_config = {
        'db_name': os.environ.get('DB_NAME'),
        'db_host': os.environ.get('DB_HOST'),
        'db_user': os.environ.get('DB_USER'),
        'db_password': os.environ.get('DB_PASSWORD')
    }
    
    # Check if all required database values are present
    missing_values = [key for key, value in db_config.items() if not value]
    if missing_values:
        raise ValueError(f"Missing required environment variables: {', '.join(missing_values)}")
    
    return db_config


def validate_arguments(args):
    """Validate the provided arguments."""
    if args.build_start_time <= 0:
        raise ValueError("Build start time must be a positive integer")
    
    if args.build_end_time <= 0:
        raise ValueError("Build end time must be a positive integer")
    
    if args.build_end_time <= args.build_start_time:
        raise ValueError("Build end time must be after build start time")
    
    if not os.path.exists(args.apk_path):
        raise ValueError(f"APK file not found at: {args.apk_path}")


def create_table_if_not_exists(conn):
    """Create the ci_data table if it doesn't exist."""
    create_table_sql = """
    CREATE TABLE IF NOT EXISTS ci_data (
        id SERIAL PRIMARY KEY,
        timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        build_duration_seconds INTEGER NOT NULL,
        apk_size_bytes BIGINT NOT NULL
    );
    """
    
    with conn.cursor() as cursor:
        cursor.execute(create_table_sql)
    conn.commit()


def insert_performance_data(conn, build_duration, apk_size):
    """Insert performance data into the ci_data table."""
    insert_sql = """
    INSERT INTO ci_data (build_duration_seconds, apk_size_bytes)
    VALUES (%s, %s)
    """
    
    with conn.cursor() as cursor:
        cursor.execute(insert_sql, (build_duration, apk_size))
    conn.commit()


def main():
    """Main function to capture CI performance metrics."""
    try:
        args = parse_arguments()
        validate_arguments(args)
        
        # Get database configuration from environment variables
        db_config = get_database_config()
        
        # Calculate build duration
        build_duration = args.build_end_time - args.build_start_time
        
        # Get APK file size
        apk_size = os.path.getsize(args.apk_path)
        
        # Convert timestamps to readable format
        start_time_str = datetime.fromtimestamp(args.build_start_time).isoformat()
        end_time_str = datetime.fromtimestamp(args.build_end_time).isoformat()
        
        print(f"Build Start Time: {start_time_str}")
        print(f"Build End Time: {end_time_str}")
        print(f"Build Duration: {build_duration} seconds")
        print(f"APK Path: {args.apk_path}")
        print(f"APK Size: {apk_size} bytes ({apk_size / (1024*1024):.2f} MB)")
        print(f"Database: {db_config['db_name']} on {db_config['db_host']} as {db_config['db_user']}")
        
        # Connect to database and insert data
        print("Connecting to database...")
        conn = psycopg.connect(
            host=db_config['db_host'],
            dbname=db_config['db_name'],
            user=db_config['db_user'],
            password=db_config['db_password']
        )
        
        # Create table if it doesn't exist
        print("Ensuring ci_data table exists...")
        create_table_if_not_exists(conn)
        
        # Insert performance data
        print("Inserting performance data...")
        insert_performance_data(conn, build_duration, apk_size)
        
        print("Performance data successfully written to ci_data table!")
        conn.close()
        
    except (ValueError, FileNotFoundError) as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)
    except psycopg.Error as e:
        print(f"Database error: {e}", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(f"Unexpected error: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
