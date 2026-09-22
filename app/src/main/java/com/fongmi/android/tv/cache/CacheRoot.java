package com.fongmi.android.tv.cache;

import java.io.File;
import java.util.Set;

public record CacheRoot(File root, boolean recursive, Set<String> includeSuffixes,
                        Set<String> excludeNames, Set<String> excludeSuffixes) {

    public static CacheRoot tree(File root) {
        return new CacheRoot(root, true, Set.of(), Set.of(), Set.of());
    }

    public static CacheRoot files(File root, Set<String> includeSuffixes, Set<String> excludeNames) {
        return new CacheRoot(root, false, includeSuffixes, excludeNames, Set.of());
    }

    public static CacheRoot orphanTree(File root, Set<String> excludeNames, Set<String> excludeSuffixes) {
        return new CacheRoot(root, true, Set.of(), excludeNames, excludeSuffixes);
    }
}
