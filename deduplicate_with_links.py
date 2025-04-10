import os
import hashlib
import argparse
import sys
import platform
from collections import defaultdict
import pathlib # Import pathlib for easier path manipulation

# Files smaller than this size will not be hashed
MIN_FILE_SIZE_TO_HASH = 1 # bytes

def hash_file(filepath, block_size=65536):
    """Calculates the SHA-256 hash of a file."""
    hasher = hashlib.sha256()
    try:
        with open(filepath, 'rb') as file:
            buf = file.read(block_size)
            while len(buf) > 0:
                hasher.update(buf)
                buf = file.read(block_size)
        return hasher.hexdigest()
    except OSError as e:
        print(f"\nError reading file {filepath}: {e}", file=sys.stderr)
        return None

def find_duplicate_files(folder_path, extensions=('.aar', '.pom', '.jar')):
    """Finds duplicate files based on size and hash."""
    if not os.path.isdir(folder_path):
        print(f"Error: Folder not found or is not a directory: {folder_path}", file=sys.stderr)
        return {}, {}

    files_by_size = defaultdict(list)
    files_scanned = 0
    print(f"Scanning folder: {folder_path}")
    print(f"Looking for extensions: {', '.join(extensions)}")

    # Use pathlib for potentially more robust path handling
    root_path = pathlib.Path(folder_path).resolve()

    for item in root_path.rglob('*'): # rglob recursively finds all files/dirs
        # Check if it's a file and has the right extension
        if item.is_file() and item.suffix.lower() in extensions:
            filepath_str = str(item) # Keep original path string for reporting/linking
            actual_filepath = item # pathlib already resolved symlinks with .resolve() above implicitly for root, but we need to handle item itself
            try:
                # Ensure we are working with the real file for size/hash
                if item.is_symlink():
                    actual_filepath = item.resolve(strict=True) # Resolve, raise error if broken
                    if not actual_filepath.is_file(): # Check if resolved target is a file
                        print(f"\nWarning: Skipping symlink pointing to non-file: {filepath_str} -> {actual_filepath}", file=sys.stderr)
                        continue
                elif not item.is_file(): # Should not happen often after initial check, but safety
                    continue

                files_scanned += 1
                if files_scanned % 500 == 0:
                    print(f"...scanned {files_scanned} potential files...", end='\r')

                file_size = actual_filepath.stat().st_size
                if file_size >= MIN_FILE_SIZE_TO_HASH:
                    # Store the original path string associated with the size
                    files_by_size[file_size].append(filepath_str)

            except FileNotFoundError:
                print(f"\nWarning: Skipping broken link: {filepath_str}", file=sys.stderr)
                continue
            except OSError as e:
                print(f"\nWarning: Could not access or get stats for {filepath_str}: {e}", file=sys.stderr)
                continue


    print(f"\nScan complete. Found {files_scanned} potential files with specified extensions.")
    print("Identifying potential duplicates based on size...")

    potential_duplicates_paths = {size: paths for size, paths in files_by_size.items() if len(paths) > 1}

    if not potential_duplicates_paths:
        print("No potential duplicates found based on file size.")
        return {}, files_by_size

    print(f"Found {len(potential_duplicates_paths)} sizes with potential duplicates. Calculating hashes...")

    files_by_hash = defaultdict(list)
    hashes_calculated = 0
    total_potential_files = sum(len(paths) for paths in potential_duplicates_paths.values())

    for size, paths in potential_duplicates_paths.items():
        for filepath_str in paths: # Use the original path string stored
            try:
                # Hash the actual file content (following links)
                # Use strict=True to catch broken links during hashing
                actual_filepath = pathlib.Path(filepath_str).resolve(strict=True)
                if not actual_filepath.is_file(): # Check again
                    continue

                file_hash = hash_file(str(actual_filepath)) # hash_file needs string path
                hashes_calculated += 1
                if hashes_calculated % 100 == 0:
                    print(f"...hashed {hashes_calculated}/{total_potential_files} potential duplicates...", end='\r')
                if file_hash:
                    # Store the original path string by hash
                    files_by_hash[file_hash].append(filepath_str)
            except FileNotFoundError:
                print(f"\nWarning: Skipping broken link during hash: {filepath_str}", file=sys.stderr)
                continue
            except OSError as e:
                print(f"\nWarning: Could not hash or resolve {filepath_str}: {e}", file=sys.stderr)


    print(f"\nHash calculation complete. Found {len(files_by_hash)} unique content hashes among potential duplicates.")

    actual_duplicates = {hash_val: paths for hash_val, paths in files_by_hash.items() if len(paths) > 1}

    return actual_duplicates, files_by_size

def replace_duplicates_with_links(duplicate_groups, dry_run=False):
    """
    Replaces duplicate files with RELATIVE symbolic links pointing to one original.

    Args:
        duplicate_groups (dict): {hash: [path1_str, path2_str, ...]}
        dry_run (bool): If True, only report actions without modifying files.

    Returns:
        tuple: (links_created, errors_encountered)
    """
    if platform.system() not in ["Linux", "Darwin"]: # Darwin is macOS
        print("\nWarning: Symbolic link creation is intended for Linux/macOS.", file=sys.stderr)
        print("Skipping replacement phase. Use --report-only on this platform.", file=sys.stderr)
        return 0, 0

    links_created = 0
    errors = 0
    group_count = 0
    total_groups = len(duplicate_groups)

    print("\n--- Starting Duplicate Replacement Phase (Relative Links) ---")
    if dry_run:
        print("*** DRY RUN enabled: No files will be deleted or linked. ***")

    for hash_val, paths_str in duplicate_groups.items():
        group_count += 1
        print(f"\nProcessing Group {group_count}/{total_groups} (Hash: {hash_val[:12]}...)")

        if len(paths_str) < 2:
            continue

        # Convert string paths to Path objects for easier manipulation
        paths_obj = [pathlib.Path(p) for p in paths_str]

        # Sort paths: prefer non-links, then alphabetically by string representation
        # Need to check if the Path object itself points to a link
        sorted_paths_obj = sorted(paths_obj, key=lambda p: (p.is_symlink(), str(p)))

        original_file_obj = sorted_paths_obj[0]
        duplicates_to_link_obj = sorted_paths_obj[1:]

        print(f"  Keeping: {original_file_obj}")
        if original_file_obj.is_symlink():
            try:
                print(f"  (Note: Kept file is itself a symbolic link -> {original_file_obj.resolve(strict=True)})")
            except FileNotFoundError:
                print(f"  (Note: Kept file is a broken symbolic link!)")
            except OSError as e:
                print(f"  (Note: Error resolving kept symlink: {e})")


        # Ensure the original file path is absolute for relpath calculation
        try:
            original_file_abs = original_file_obj.resolve(strict=True) # Need real path of target
        except (OSError, FileNotFoundError) as e:
            print(f"  Error: Cannot resolve path for chosen original file '{original_file_obj}'. Skipping this group. Error: {e}", file=sys.stderr)
            errors += len(duplicates_to_link_obj) # Count all potential links as errors for this group
            continue


        for link_path_obj in duplicates_to_link_obj:
            link_path_str = str(link_path_obj) # Keep string for os functions if needed
            print(f"  Replacing: {link_path_str}")

            try:
                # Get the directory where the link will be created
                link_directory_abs = link_path_obj.parent.resolve(strict=True) # Absolute path of link's directory

                # Calculate the relative path from the link's directory to the original file's absolute path
                relative_target_path = os.path.relpath(str(original_file_abs), str(link_directory_abs))

                if dry_run:
                    print(f"    DRY RUN: Would delete '{link_path_str}'")
                    print(f"    DRY RUN: Would create symlink '{link_path_str}' -> '{relative_target_path}'")
                    links_created += 1 # Count intended actions in dry run
                    continue

                # --- Perform File Operations ---
                # 1. Remove the duplicate file/link
                # Use unlink for both files and links, rmdir for directories if needed later
                link_path_obj.unlink() # Replaces os.remove, works for files and links

                # 2. Create the symbolic link using the relative path
                # os.symlink needs string paths
                os.symlink(relative_target_path, link_path_str)

                print(f"    Success: Replaced with symlink -> {relative_target_path}")
                links_created += 1
            except FileNotFoundError:
                # Handle case where parent directory doesn't exist (shouldn't happen if file existed)
                # or if original_file_abs or link_directory_abs resolution failed (caught above/unlikely here)
                print(f"    Error replacing {link_path_str}: File or directory not found during operation.", file=sys.stderr)
                errors += 1
            except OSError as e:
                print(f"    Error replacing {link_path_str}: {e}", file=sys.stderr)
                errors += 1
            except Exception as e: # Catch any other unexpected errors
                print(f"    Unexpected error replacing {link_path_str}: {e}", file=sys.stderr)
                errors += 1

    print("\n--- Replacement Phase Summary ---")
    if dry_run:
        print(f"DRY RUN: Would have attempted to create {links_created} relative links.")
    else:
        print(f"Successfully created {links_created} relative symbolic links.")
    if errors > 0:
        print(f"Encountered {errors} errors during replacement.")

    return links_created, errors


# --- Main Execution ---
if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="Find duplicate files (.aar, .pom, .jar) and optionally replace duplicates with RELATIVE symbolic links.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
WARNING: The --link option modifies your file system by deleting files and creating symbolic links.
It is STRONGLY recommended to back up your target directory or test on a copy first.
This feature is intended for Linux/macOS systems. Relative links enhance portability.
"""
    )
    parser.add_argument("folder", help="The root folder to scan recursively.")
    parser.add_argument("--link", action="store_true",
                        help="Replace duplicate files with relative symbolic links. Requires confirmation unless --yes is used.")
    parser.add_argument("--dry-run", action="store_true",
                        help="Perform a dry run of the --link action: report what would be done without changing files.")
    parser.add_argument("--yes", "-y", action="store_true",
                        help="Bypass confirmation prompt when using --link (use with caution!).")

    args = parser.parse_args()

    # --- Find Duplicates ---
    # duplicate_groups maps hash -> list of original path strings
    duplicate_groups, files_by_size_lookup = find_duplicate_files(args.folder)

    if not duplicate_groups:
        print("\nNo duplicate files found.")
        sys.exit(0)

    # --- Reporting Phase ---
    print("\n--- Duplicate File Report ---")
    total_duplicates_found = 0
    total_wasted_space_found = 0
    report_group_count = 0

    sorted_duplicate_info = []
    for hash_val, paths_str in duplicate_groups.items():
        if paths_str:
            try:
                # Use realpath (via pathlib.resolve) to get size of the actual file content
                # Need strict=True to ensure we don't calculate size of broken link target dir
                size_bytes = pathlib.Path(paths_str[0]).resolve(strict=True).stat().st_size
                sorted_duplicate_info.append((size_bytes, hash_val, paths_str))
            except (OSError, FileNotFoundError) as e:
                print(f"\nWarning: Could not get size for group with hash {hash_val[:8]}... ({paths_str[0]}): {e}", file=sys.stderr)
                sorted_duplicate_info.append((-1, hash_val, paths_str)) # Assign dummy size

    sorted_duplicate_info.sort(key=lambda item: (item[0], len(item[2])), reverse=True)

    for size_bytes, hash_val, paths_str in sorted_duplicate_info:
        report_group_count += 1
        num_duplicates = len(paths_str)
        total_duplicates_found += num_duplicates
        if size_bytes >= 0:
            wasted_space_group = size_bytes * (num_duplicates - 1)
            total_wasted_space_found += wasted_space_group
        else:
            wasted_space_group = -1 # Indicate unknown

        # Format size string (same as before)
        if size_bytes < 0: size_str = "Unknown Size"
        elif size_bytes < 1024: size_str = f"{size_bytes} B"
        elif size_bytes < 1024 * 1024: size_str = f"{size_bytes / 1024:.2f} KB"
        elif size_bytes < 1024 * 1024 * 1024: size_str = f"{size_bytes / (1024 * 1024):.2f} MB"
        else: size_str = f"{size_bytes / (1024 * 1024 * 1024):.2f} GB"

        print(f"\nGroup {report_group_count}: Found {num_duplicates} duplicates (Size: {size_str}, Hash: {hash_val[:12]}...)")
        for filepath_str in sorted(paths_str):
            link_info = ""
            try: # Add check to show if an item in the report is already a link
                path_obj = pathlib.Path(filepath_str)
                if path_obj.is_symlink():
                    link_target = os.readlink(filepath_str) # readlink shows the raw link target
                    link_info = f" -> {link_target}"
            except OSError:
                link_info = " [Error reading link]"
            print(f"  - {filepath_str}{link_info}")


    print("\n--- Reporting Summary ---")
    print(f"Found {report_group_count} groups of duplicate files.")
    print(f"Total number of duplicate file instances found: {total_duplicates_found}")

    # Format wasted space string (same as before)
    if total_wasted_space_found < 0: wasted_str = "Cannot calculate accurately due to size errors."
    elif total_wasted_space_found < 1024: wasted_str = f"{total_wasted_space_found} B"
    elif total_wasted_space_found < 1024 * 1024: wasted_str = f"{total_wasted_space_found / 1024:.2f} KB"
    elif total_wasted_space_found < 1024 * 1024 * 1024: wasted_str = f"{total_wasted_space_found / (1024 * 1024):.2f} MB"
    else: wasted_str = f"{total_wasted_space_found / (1024 * 1024 * 1024):.2f} GB"
    print(f"Estimated space potentially recoverable: {wasted_str}")

    # --- Linking Phase (Conditional) ---
    if args.link or args.dry_run:
        if args.dry_run:
            replace_duplicates_with_links(duplicate_groups, dry_run=True)
        elif args.link:
            print("\nWARNING: This action will DELETE files and replace them with RELATIVE symbolic links.")
            print("Target directory:", os.path.abspath(args.folder))
            if not args.yes:
                confirm = input("Are you sure you want to proceed? (yes/no): ")
                if confirm.lower() != 'yes':
                    print("Aborted by user.")
                    sys.exit(0)

            # Proceed with linking
            replace_duplicates_with_links(duplicate_groups, dry_run=False)
        # else: # Redundant check
        #      pass
    else:
        print("\nRun with --link to replace duplicates with relative symlinks (or --dry-run to preview).")