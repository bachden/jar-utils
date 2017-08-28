package nhb.cracking.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import org.apache.commons.io.FileExistsException;
import org.apache.commons.io.FileUtils;

public class JarUtils {

	public static String extractJarFile(String jarFilePath, String outputFolderPath) throws Exception {
		if (jarFilePath == null) {
			throw new NullPointerException("Input file cannot be null");
		}

		File jarFile = new File(jarFilePath);
		if (!jarFile.exists()) {
			throw new FileNotFoundException("File not found at " + jarFilePath);
		}

		if (outputFolderPath == null) {
			File currentDirectory = new File(new File("").getAbsolutePath());
			outputFolderPath = currentDirectory.getAbsolutePath();
		}

		File outputFolder = new File(outputFolderPath + File.separator + jarFile.getName());

		if (!outputFolder.exists()) {
			outputFolder.mkdirs();
		}

		final ExecutorService executor = Executors.newFixedThreadPool(16);

		try (JarFile jar = new JarFile(jarFilePath)) {
			Enumeration<JarEntry> enumEntries = jar.entries();
			final List<Runnable> tasks = new ArrayList<>();

			final AtomicInteger completedTaskCount = new AtomicInteger(0);
			final AtomicInteger extractedFileCount = new AtomicInteger(0);
			final CountDownLatch doneSignal = new CountDownLatch(1);

			while (enumEntries.hasMoreElements()) {

				final JarEntry sourceFile = enumEntries.nextElement();
				final File targetFile = new File(
						outputFolder.getAbsolutePath() + File.separator + sourceFile.getName());

				if (sourceFile.isDirectory()) { // if its a directory, create it
					System.out.println("Create folder: " + targetFile.getAbsolutePath());
					targetFile.mkdirs();
				} else {
					if (!targetFile.getParentFile().exists()) {
						System.out.println("Create folder: " + targetFile.getAbsolutePath());
						targetFile.getParentFile().mkdirs();
					}
					tasks.add(createUnjarTask(jar, tasks, completedTaskCount, extractedFileCount, doneSignal,
							sourceFile, targetFile));
				}
			}

			for (Runnable task : tasks) {
				executor.submit(task);
			}

			doneSignal.await();
			System.out.println(
					"Extracted " + extractedFileCount.get() + " files to folder: " + outputFolder.getAbsolutePath());
		} finally {
			executor.shutdown();
			if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
				executor.shutdownNow();
			}
		}
		return outputFolder.getPath();
	}

	private static Runnable createUnjarTask(final JarFile jar, final List<Runnable> tasks,
			final AtomicInteger completedTaskCount, final AtomicInteger extractedFileCount,
			final CountDownLatch doneSignal, final JarEntry sourceFile, final File targetFile) {
		return new Runnable() {

			private final File _targetFile = targetFile;
			private final JarEntry _sourceFile = sourceFile;

			@Override
			public void run() {
				try {
					if (this._targetFile.exists()) {
						throw new FileExistsException(
								"File already exist with name: " + this._targetFile.getAbsolutePath());
					}
					this._targetFile.createNewFile();
					try (InputStream is = jar.getInputStream(this._sourceFile);
							FileOutputStream fos = new FileOutputStream(this._targetFile)) {
						System.out.println("Extracting file: " + this._targetFile.getAbsolutePath() + " from source: "
								+ this._sourceFile.getName());
						while (is.available() > 0) {
							fos.write(is.read());
						}
						extractedFileCount.incrementAndGet();
					}
				} catch (IOException e1) {
					e1.printStackTrace();
				} finally {
					if (completedTaskCount.incrementAndGet() == tasks.size()) {
						doneSignal.countDown();
					}
				}
			}
		};
	}

	public static String compressJarFile(String sourceFolderPath, String outputFilePath) throws IOException {
		File source = new File(sourceFolderPath);
		sourceFolderPath = source.getAbsolutePath();

		Collection<File> files = FileUtils.listFiles(new File(sourceFolderPath), null, true);
		Map<String, File> jarEntryToSource = new HashMap<>();
		for (File file : files) {
			String sourceFileAbsolutePath = file.getAbsolutePath();
			String entryPath = sourceFileAbsolutePath.substring(sourceFolderPath.length() + 1);
			jarEntryToSource.put(entryPath, file);
		}

		File outputFile = new File(outputFilePath);
		if (outputFile.exists()) {
			outputFile.delete();
		} else if (!outputFile.getParentFile().exists()) {
			outputFile.getParentFile().mkdirs();
		}

		byte[] buffer = new byte[1024];
		try (FileOutputStream fileOutStream = new FileOutputStream(outputFile);
				JarOutputStream jarOutStream = new JarOutputStream(fileOutStream)) {

			for (Entry<String, File> entry : jarEntryToSource.entrySet()) {
				JarEntry ze = new JarEntry(entry.getKey());
				jarOutStream.putNextEntry(ze);
				try (InputStream in = new FileInputStream(entry.getValue())) {
					int len;
					while ((len = in.read(buffer)) > 0) {
						jarOutStream.write(buffer, 0, len);
					}
				}
			}
		}
		return outputFile.getAbsolutePath();
	}
}
