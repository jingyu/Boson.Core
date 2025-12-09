/*
 * Copyright (c) 2023 -      bosonnetwork.io
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.bosonnetwork.utils;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Utility class for common file and directory operations.
 * <p>
 * This class provides static utility methods for:
 * <ul>
 *   <li>File and directory deletion (including recursive deletion)</li>
 *   <li>Path normalization and resolution</li>
 *   <li>Platform-specific configuration and data directory retrieval</li>
 * </ul>
 *
 * <p>
 * All methods in this class are static and the class cannot be instantiated.
 */
public class FileUtils {
	/**
	 * Deletes the specified file or directory from the file system.
	 * <p>
	 * If the path points to a directory, this method recursively deletes all contents
	 * (files and subdirectories) before deleting the directory itself. If the path does
	 * not exist, this method returns without throwing an exception.
	 *
	 * @param file the {@link Path} of the file or directory to delete, must not be null
	 * @throws IOException if an I/O error occurs while deleting the file or directory
	 */
	public static void deleteFile(Path file) throws IOException {
		if (Files.notExists(file))
			return;

		Files.walkFileTree(file, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public  FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
				Files.delete(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/**
	 * Normalizes and resolves a file system path.
	 * <p>
	 * This method performs the following operations:
	 * <ul>
	 *   <li>Normalizes the path (removes redundant elements like "." and "..")</li>
	 *   <li>Returns absolute paths unchanged (after normalization)</li>
	 *   <li>Expands tilde (~) prefix to the user's home directory</li>
	 *   <li>Returns relative paths unchanged (after normalization)</li>
	 * </ul>
	 *
	 * @param path the path to normalize, may be null
	 * @return the normalized path, or null if the input path is null
	 */
	public static Path normalizePath(Path path) {
		if (path != null) {
			path = path.normalize();
			if (path.isAbsolute())
				return path;

			if (path.startsWith("~")) {
				path = Path.of(System.getProperty("user.home")).resolve(path.subpath(1, path.getNameCount()));
				return path;
			}
		}

		return path;
	}

	/**
	 * Returns the system-wide configuration directory for the current platform.
	 * <p>
	 * This directory is typically used for configuration files that apply to all users
	 * on the system and require administrative privileges to modify.
	 *
	 * <p>
	 * Platform-specific locations:
	 * <ul>
	 *   <li>Windows: %ProgramData% (e.g., {@code C:\\ProgramData})</li>
	 *   <li>Linux: {@code /etc}</li>
	 *   <li>macOS: {@code /Library/Preferences} (follows macOS conventions for system-level config)</li>
	 * </ul>
	 *
	 * @return the system-wide configuration directory as a {@link Path}
	 */
	public static Path getSystemConfigDir() {
		String osName = System.getProperty("os.name").toLowerCase();
		if (osName.startsWith("windows")) {
			return Path.of(System.getenv("ProgramData"));
		} else if (osName.startsWith("mac")) {
			return Path.of("/Library/Preferences");
		} else {
			// Unix like OS
			return Path.of("/etc");
		}
	}

	/**
	 * Returns the system-local configuration directory for locally installed software.
	 * <p>
	 * This directory is typically used for configuration files of software installed locally
	 * (not part of the base OS distribution) that apply to all users on the system.
	 *
	 * <p>
	 * Platform-specific locations:
	 * <ul>
	 *   <li>Windows: %ProgramData% (e.g., {@code C:\\ProgramData})</li>
	 *   <li>Linux: {@code /usr/local/etc}</li>
	 *   <li>macOS: {@code /Library/Application Support} (follows macOS conventions for system-level config)</li>
	 * </ul>
	 *
	 * @return the system-local configuration directory as a {@link Path}
	 */
	public static Path getSystemLocalConfigDir() {
		String osName = System.getProperty("os.name").toLowerCase();
		if (osName.startsWith("windows")) {
			return Path.of(System.getenv("ProgramData"));
		} else if (osName.startsWith("mac")) {
			return Path.of("/Library/Application Support");
		} else {
			// Unix like OS
			return Path.of("/usr/local/etc");
		}
	}

	/**
	 * Returns the per-user configuration directory for the current platform.
	 * <p>
	 * This directory is used for storing user-specific configuration files that don't
	 * require administrative privileges to modify. On Unix-like systems, this follows
	 * the XDG Base Directory specification.
	 *
	 * <p>
	 * Platform-specific locations:
	 * <ul>
	 *   <li>Windows: %APPDATA% (e.g., {@code C:\\Users\\username\\AppData\\Roaming})</li>
	 *   <li>Linux: {@code ~/.config}</li>
	 *   <li>macOS: {@code ~/.config} (intentionally using XDG style instead of {@code ~/Library/Preferences})</li>
	 * </ul>
	 *
	 * @return the per-user configuration directory as a {@link Path}
	 */
	public static Path getUserConfigDir() {
		String osName = System.getProperty("os.name").toLowerCase();
		if (osName.startsWith("windows")) {
			return Path.of(System.getenv("APPDATA"));
		} else if (osName.startsWith("mac")) {
			// return Path.of(System.getProperty("user.home"), "Library/Preferences");
			return Path.of(System.getProperty("user.home"), ".config");
		} else {
			// Unix like OS
			return Path.of(System.getProperty("user.home"), ".config");
		}
	}

	/**
	 * Returns the system-wide persistent data directory for the current platform.
	 * <p>
	 * This directory is typically used for storing application data that applies to all
	 * users on the system, such as databases, caches, and other persistent state that
	 * requires administrative privileges to modify.
	 *
	 * <p>
	 * Platform-specific locations:
	 * <ul>
	 *   <li>Windows: %ProgramData% (e.g., {@code C:\\ProgramData})</li>
	 *   <li>Linux: {@code /var/lib}</li>
	 *   <li>macOS: {@code /Library/Application Support} (follows macOS conventions for system-level data)</li>
	 * </ul>
	 *
	 * @return the system-wide persistent data directory as a {@link Path}
	 */
	public static Path getSystemDataDir() {
		String osName = System.getProperty("os.name").toLowerCase();
		if (osName.startsWith("windows")) {
			return Path.of(System.getenv("ProgramData"));
		} else if (osName.startsWith("mac")) {
			return Path.of("/Library/Application Support");
		} else {
			// Unix like OS
			return Path.of("/var/lib");
		}
	}

	/**
	 * Returns the per-user persistent data directory for the current platform.
	 * <p>
	 * This directory is used for storing user-specific application data such as databases,
	 * caches, and other persistent state. On Unix-like systems, this follows the XDG Base
	 * Directory specification.
	 *
	 * <p>
	 * Platform-specific locations:
	 * <ul>
	 *   <li>Windows: %LOCALAPPDATA% (e.g., {@code C:\\Users\\username\\AppData\\Local})</li>
	 *   <li>Linux: {@code ~/.local/share}</li>
	 *   <li>macOS: {@code ~/.local/share} (intentionally using XDG style instead of {@code ~/Library/Application Support})</li>
	 * </ul>
	 *
	 * @return the per-user persistent data directory as a {@link Path}
	 */
	public static Path getUserDataDir() {
		String osName = System.getProperty("os.name").toLowerCase();
		if (osName.startsWith("windows")) {
			return Path.of(System.getenv("LOCALAPPDATA"));
		} else if (osName.startsWith("mac")) {
			//return Path.of(System.getProperty("user.home"), "Library/Application Support");
			return Path.of(System.getProperty("user.home"), ".local/share");
		} else {
			// Unix like OS
			return Path.of(System.getProperty("user.home"), ".local/share");
		}
	}
}