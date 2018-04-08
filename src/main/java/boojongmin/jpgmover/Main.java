package boojongmin.jpgmover;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.ExifIFD0Directory;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Marker;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

@Slf4j
public class Main {
    static Map<String, List<MetaInfo>> result = new ConcurrentHashMap<>();
    static File source;
    static File destination;
    static Set<String> duplicateSet = ConcurrentHashMap.newKeySet();
    static ForkJoinPool pool = ForkJoinPool.commonPool();
    public static void main(String[] args) throws Exception {
        elapsedTimeChecker(() -> {
            checkInput(args);
            walkFiles(source);
            printReport();
        }, "1. collect file info" );
        pool.shutdown();

        pool.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
        elapsedTimeChecker(() -> {
            moveJpgWithErrorHandling();
        }, "2. move files");
        System.out.println("complete");
    }

//    private static void runStep1(String[] args) {
//        try {
//
//        } catch (Exception e) {
//            System.out.printf("[error step 1] %s\n", e.getMessage());
//        }
//    }
//
//    private static void runStep2() {
//        try {
//
//        } catch (Exception e) {
//            System.out.printf("[error step 2] %s\n", e.getMessage());
//        }
//    }

    private static void elapsedTimeChecker(FacadConsumer fn, String stageName) throws Exception {
        System.out.printf("start -> %s\n", stageName);
        long startTime = System.currentTimeMillis();
        fn.run(stageName);
        long totalTime = System.currentTimeMillis() - startTime;
        System.out.printf("end -> %s, elapsed time %d(ms)\n", stageName, totalTime);
    }


    private static void printReport() {
        System.out.printf("total image count is %d\n", result.size());
        System.out.printf("total image size is %.2f Mb\n",
            result.values().stream().map(x ->
                x.stream()
                    .map(y -> y.getFile().length())
                    .reduce(0L, Long::sum)
            )
            .reduce(0L, Long::sum)
            .doubleValue() / (1000 * 1000)
        )
        ;
    }

    private static void checkInput(String[] args) {
        if(args.length != 2) {
            System.out.println("[error] your input size is not 2.\nexit\n");
            System.exit(0);
        }
        source = new File(args[0]);
        destination = new File(args[1]);
        if(!source.exists()) {
            System.out.println("first argument is wrong. check image path");
        }

    }

    private static void moveJpgWithErrorHandling() {
        try {
            moveJpg();
        } catch (Exception e) {
            System.out.printf("[error when move jpg] %s\n", e.getMessage());
        }
    }

    private static void moveJpg() throws URISyntaxException, IOException, InterruptedException {
        if(!destination.exists()) {
            destination.mkdirs();
        }
        ForkJoinPool copyPool = ForkJoinPool.commonPool();
        Path path = Paths.get(destination.toURI());

        for (String key : result.keySet()) {
            Path dir = path.resolve(key);
            File file = dir.toFile();
            if(!file.exists()) {
                file.mkdirs();
            }
            List<MetaInfo> infos = result.get(key);
            for (MetaInfo info : infos) {
                File target = info.getFile();
                copyPool.submit(() -> {
                    try {
                        Files.copy(Paths.get(target.toURI()), dir.resolve(target.getName()), StandardCopyOption.REPLACE_EXISTING);
                    } catch (Exception e) {
                        System.out.printf("[copy error] folder: %s, file name: %s\n", dir.toFile().getAbsolutePath(), target.getName());
                        log.error("file copy error", e);
                    }
                });
            }
            copyPool.shutdown();
            copyPool.awaitTermination(Long.MAX_VALUE, TimeUnit.MICROSECONDS);
        }
    }

    private static void processMetaInfoWithHandleException(File file) {
        try {
            processMetaInfo(file);
        } catch (Exception e) {
            System.out.println(e.getMessage());
            log.error("error file path :  %s", file.getAbsolutePath());
            log.error("processMetaInfoWithHandleException", e);
        }
    }

    private static void processMetaInfo(File file) throws ImageProcessingException, IOException, ParseException {
        Metadata metadata = ImageMetadataReader.readMetadata(file);
        Collection<ExifIFD0Directory> exifIFD0Directories = metadata.getDirectoriesOfType(ExifIFD0Directory.class);
        SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");
        MetaInfo metaInfo = new MetaInfo();
        metaInfo.setFile(file);
        for (ExifIFD0Directory directory : exifIFD0Directories) {
            for (Tag tag : directory.getTags()) {
                if(tag.getTagName().equals("Model")) {
                    metaInfo.setModel(tag.getDescription());
                } else if(tag.getTagName().equals("Date/Time")) {
                    Date created = inputFormat.parse(tag.getDescription());
                    metaInfo.setCreated(created);
                }
            }
        }
        String sha256Hex = DigestUtils.sha256Hex(Files.readAllBytes(file.toPath()));
        if(!duplicateSet.contains(sha256Hex)) {
            duplicateSet.add(sha256Hex);
            putInfo(metaInfo);
        }
    }


    private static void putInfo(MetaInfo metaInfo) {
        SimpleDateFormat outFormat = new SimpleDateFormat("yyyyMMdd");
        String key = metaInfo.getModel() + File.separator + outFormat.format(metaInfo.getCreated());
        result.computeIfAbsent(key, k -> new ArrayList<>()).add(metaInfo);
    }

    private static void walkFiles(File f) throws IOException {
        if(f.isDirectory()) {
            File[] files = f.listFiles();
            for (File file : files) {
                walkFiles(file);
            }
        } else {
            pool.execute(() -> {
                Optional<String> contentTypeOpt = null;
                try {
                    contentTypeOpt = Optional.ofNullable(Files.probeContentType(f.toPath()));
                    contentTypeOpt.ifPresent(x -> {
                        if(x.startsWith("image")) processMetaInfoWithHandleException(f);
                    });
                } catch (Exception e) {
                    System.out.printf("[error when work files] %s\n", e.getMessage());
                }
            });
        }
    }
}

@Data
class MetaInfo {
    private String model;
    private Date created;
    private File file;
}

@FunctionalInterface
interface FacadConsumer {
    void run() throws Exception;

    default void run(String stageName) {
        long startTime = System.currentTimeMillis();
        try {
            run();
        } catch(Exception e) {
            System.out.printf("[error %s] %s", stageName, e.getMessage());
        }
        long endTime = System.currentTimeMillis();
        long totalTime =  endTime - startTime;
        System.out.printf("[elapsed time] %s : %d(ms)\n", stageName, totalTime);
    }
}
