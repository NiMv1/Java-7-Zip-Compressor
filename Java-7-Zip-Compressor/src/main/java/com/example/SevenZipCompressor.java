package com.example;

import javax.swing.*;
import javax.swing.filechooser.FileSystemView;
import java.awt.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class SevenZipCompressor {

    private static final int MAX_THREADS = 10;
    private static final String SEVEN_ZIP_EXECUTABLE = "C:\\Program Files\\7-Zip\\7zG.exe"; // Example path

    private JFrame frame;
    private JProgressBar overallProgressBar;
    private JTextArea logTextArea;
    private JButton compressButton;
    private JButton cancelButton;
    private DefaultListModel<Path> selectedFoldersModel;
    private JList<Path> selectedFoldersList;
    private int numThreads;
    private CompressionWorker compressionWorker;

    public SevenZipCompressor() {
        initializeGUI();
    }

    private void initializeGUI() {
        frame = new JFrame("Seven Zip Compressor");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel mainPanel = new JPanel();
        GroupLayout layout = new GroupLayout(mainPanel);
        mainPanel.setLayout(layout);
        layout.setAutoCreateGaps(true);
        layout.setAutoCreateContainerGaps(true);

        // Components
        selectedFoldersModel = new DefaultListModel<>();
        selectedFoldersList = new JList<>(selectedFoldersModel);
        JScrollPane selectedFoldersScrollPane = new JScrollPane(selectedFoldersList);

        JButton addButton = new JButton("Add Folders");
        addButton.addActionListener(e -> {
            JFileChooser folderChooser = new JFileChooser(FileSystemView.getFileSystemView().getHomeDirectory());
            folderChooser.setDialogTitle("Select folders to compress");
            folderChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            folderChooser.setMultiSelectionEnabled(true);

            int returnValue = folderChooser.showOpenDialog(null);
            if (returnValue == JFileChooser.APPROVE_OPTION) {
                for (File folder : folderChooser.getSelectedFiles()) {
                    selectedFoldersModel.addElement(folder.toPath());
                }
            }
        });

        JButton removeButton = new JButton("Remove Selected");
        removeButton.addActionListener(e -> {
            int selectedIndex = selectedFoldersList.getSelectedIndex();
            if (selectedIndex != -1) {
                selectedFoldersModel.remove(selectedIndex);
            }
        });

        JComboBox<Integer> threadComboBox = new JComboBox<>();
        for (int i = 1; i <= MAX_THREADS; i++) {
            threadComboBox.addItem(i);
        }
        threadComboBox.setSelectedItem(2); // Default to 2 threads

        overallProgressBar = new JProgressBar(0, 100);
        overallProgressBar.setStringPainted(true);

        logTextArea = new JTextArea(10, 50);
        logTextArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(logTextArea);

        JLabel threadsLabel = new JLabel("Number of threads:");

        compressButton = new JButton("Compress");
        compressButton.addActionListener(e -> {
            List<Path> selectedFolders = selectedFoldersList.getSelectedValuesList();
            if (selectedFolders.isEmpty()) {
                logMessage("Please select folders to compress.");
                return;
            }

            numThreads = (int) threadComboBox.getSelectedItem();

            // Disable UI components during compression
            setUIEnabled(false);

            // Create and execute compression worker
            compressionWorker = new CompressionWorker(selectedFolders, numThreads);
            compressionWorker.execute();
        });

        cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> {
            if (compressionWorker != null && !compressionWorker.isDone()) {
                compressionWorker.cancel(true);
                logMessage("Compression canceled.");
            }
        });
        cancelButton.setEnabled(false);

        // GroupLayout setup
        layout.setHorizontalGroup(layout.createParallelGroup()
                .addComponent(selectedFoldersScrollPane)
                .addGroup(layout.createSequentialGroup()
                        .addComponent(addButton)
                        .addComponent(removeButton))
                .addGroup(layout.createSequentialGroup()
                        .addComponent(threadsLabel)
                        .addComponent(threadComboBox))
                .addComponent(overallProgressBar)
                .addComponent(scrollPane)
                .addGroup(layout.createSequentialGroup()
                        .addComponent(compressButton)
                        .addComponent(cancelButton))
        );

        layout.setVerticalGroup(layout.createSequentialGroup()
                .addComponent(selectedFoldersScrollPane)
                .addGroup(layout.createParallelGroup(GroupLayout.Alignment.BASELINE)
                        .addComponent(addButton)
                        .addComponent(removeButton))
                .addGroup(layout.createParallelGroup(GroupLayout.Alignment.BASELINE)
                        .addComponent(threadsLabel)
                        .addComponent(threadComboBox))
                .addComponent(overallProgressBar)
                .addComponent(scrollPane)
                .addGroup(layout.createParallelGroup(GroupLayout.Alignment.BASELINE)
                        .addComponent(compressButton)
                        .addComponent(cancelButton))
        );

        frame.add(mainPanel);
        frame.setSize(600, 700);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private class CompressionWorker extends SwingWorker<Void, Integer> {

        private List<Path> selectedFolders;
        private int numThreads;
        private long totalFolders;
        private AtomicLong completedFoldersCount;

        public CompressionWorker(List<Path> selectedFolders, int numThreads) {
            this.selectedFolders = selectedFolders;
            this.numThreads = numThreads;
            this.totalFolders = 0;
            this.completedFoldersCount = new AtomicLong(0);
        }

        @Override
        protected Void doInBackground() throws Exception {
            totalFolders = selectedFolders.size();

            ExecutorService executorService = Executors.newFixedThreadPool(numThreads);
            List<Future<?>> futures = new CopyOnWriteArrayList<>();

            for (Path subfolder : selectedFolders) {
                if (Thread.currentThread().isInterrupted()) {
                    logMessage("Compression interrupted.");
                    break;
                }

                Path outputFile = subfolder.getParent().resolve(subfolder.getFileName() + ".7z");
                Callable<Void> task = () -> {
                    compressFolder(subfolder, outputFile);
                    long completed = completedFoldersCount.incrementAndGet();
                    int overallProgress = (int) ((completed * 100) / totalFolders);
                    publish(overallProgress);
                    return null;
                };
                futures.add(executorService.submit(task));
            }

            executorService.shutdown();

            try {
                executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
                logMessage("All folders compressed successfully.");
            } catch (InterruptedException e) {
                logMessage("Compression process interrupted.\n" + e.getMessage());
            }

            return null;
        }

        @Override
        protected void process(List<Integer> chunks) {
            for (Integer progress : chunks) {
                overallProgressBar.setValue(progress);
            }
        }

        @Override
        protected void done() {
            // Enable UI components after compression
            setUIEnabled(true);
        }
    }

    private void compressFolder(Path folder, Path outputFile) {
        try {
            ProcessBuilder pb = new ProcessBuilder(SEVEN_ZIP_EXECUTABLE, "a", "-t7z", "-mx=9", outputFile.toString(), folder.toString());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logMessage(line);
                }
            }

            process.waitFor();

            if (process.exitValue() == 0) {
                deleteFolder(folder);
                logMessage("Folder " + folder.getFileName() + " compressed and deleted successfully.");
            } else {
                logMessage("Error compressing folder " + folder.getFileName() + " with exit code " + process.exitValue());
            }
        } catch (IOException | InterruptedException e) {
            logMessage("Error compressing folder " + folder.getFileName() + "\n" + e.getMessage());
        }
    }

    private void deleteFolder(Path folder) {
        try {
            Files.walkFileTree(folder, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            logMessage("Error deleting folder " + folder + "\n" + e.getMessage());
        }
    }

    private void logMessage(String message) {
        SwingUtilities.invokeLater(() -> {
            logTextArea.append(message + "\n");
            logTextArea.setCaretPosition(logTextArea.getDocument().getLength());
        });
    }

    private void setUIEnabled(boolean enabled) {
        overallProgressBar.setIndeterminate(!enabled);
        logTextArea.setEnabled(enabled);
        compressButton.setEnabled(enabled);
        cancelButton.setEnabled(!enabled);
        frame.setCursor(enabled ? Cursor.getDefaultCursor() : Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(SevenZipCompressor::new);
    }
}