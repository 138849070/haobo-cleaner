package com.haobo.cleaner;

interface IShellService {
    String exec(String command);
    long sizeOf(String path);
    String[] listChildren(String path);
    String[] scanFiles(String root, in String[] includeGlobs, in String[] excludeGlobs, in String[] excludeDirNames, long minSizeBytes, int maxDepth, int maxResults);
    boolean deletePath(String path);
    long freeBytes();
}
