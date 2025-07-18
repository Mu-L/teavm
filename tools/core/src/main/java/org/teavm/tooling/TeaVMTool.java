/*
 *  Copyright 2014 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.tooling;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.teavm.backend.c.CTarget;
import org.teavm.backend.c.generate.CNameProvider;
import org.teavm.backend.c.generate.ShorteningFileNameProvider;
import org.teavm.backend.c.generate.SimpleFileNameProvider;
import org.teavm.backend.javascript.JSModuleType;
import org.teavm.backend.javascript.JavaScriptTarget;
import org.teavm.backend.wasm.WasmDebugInfoLevel;
import org.teavm.backend.wasm.WasmDebugInfoLocation;
import org.teavm.backend.wasm.WasmGCTarget;
import org.teavm.backend.wasm.WasmRuntimeType;
import org.teavm.backend.wasm.WasmTarget;
import org.teavm.backend.wasm.debug.sourcemap.SourceMapBuilder;
import org.teavm.backend.wasm.render.WasmBinaryVersion;
import org.teavm.cache.AlwaysStaleCacheStatus;
import org.teavm.cache.CacheStatus;
import org.teavm.cache.DiskCachedClassReaderSource;
import org.teavm.cache.DiskMethodNodeCache;
import org.teavm.cache.DiskProgramCache;
import org.teavm.cache.EmptyProgramCache;
import org.teavm.cache.FileSymbolTable;
import org.teavm.debugging.information.DebugInformation;
import org.teavm.debugging.information.DebugInformationBuilder;
import org.teavm.debugging.information.SourceMapsWriter;
import org.teavm.dependency.DependencyInfo;
import org.teavm.dependency.FastDependencyAnalyzer;
import org.teavm.dependency.PreciseDependencyAnalyzer;
import org.teavm.diagnostics.ProblemProvider;
import org.teavm.model.ClassHolderSource;
import org.teavm.model.ClassHolderTransformer;
import org.teavm.model.ClassReader;
import org.teavm.model.PreOptimizingClassHolderSource;
import org.teavm.model.ReferenceCache;
import org.teavm.model.transformation.AssertionRemoval;
import org.teavm.parsing.ClasspathClassHolderSource;
import org.teavm.parsing.ClasspathResourceProvider;
import org.teavm.parsing.resource.ResourceProvider;
import org.teavm.tooling.sources.DefaultSourceFileResolver;
import org.teavm.tooling.sources.SourceFileProvider;
import org.teavm.vm.BuildTarget;
import org.teavm.vm.DirectoryBuildTarget;
import org.teavm.vm.TeaVM;
import org.teavm.vm.TeaVMBuilder;
import org.teavm.vm.TeaVMOptimizationLevel;
import org.teavm.vm.TeaVMProgressListener;
import org.teavm.vm.TeaVMTarget;

public class TeaVMTool {
    private File targetDirectory = new File(".");
    private TeaVMTargetType targetType = TeaVMTargetType.JAVASCRIPT;
    private String targetFileName = "";
    private boolean obfuscated = true;
    private JSModuleType jsModuleType = JSModuleType.UMD;
    private boolean strict;
    private int maxTopLevelNames = 80_000;
    private String mainClass;
    private String entryPointName = "main";
    private Properties properties = new Properties();
    private boolean debugInformationGenerated;
    private boolean sourceMapsFileGenerated;
    private TeaVMSourceFilePolicy sourceFilePolicy = TeaVMSourceFilePolicy.DO_NOTHING;
    private boolean incremental;
    private File cacheDirectory = new File("./teavm-cache");
    private List<String> transformers = new ArrayList<>();
    private List<String> classesToPreserve = new ArrayList<>();
    private TeaVMToolLog log = new EmptyTeaVMToolLog();
    private ClassLoader classLoader = TeaVMTool.class.getClassLoader();
    private List<File> classPath;
    private DiskCachedClassReaderSource cachedClassSource;
    private DiskProgramCache programCache;
    private DiskMethodNodeCache astCache;
    private FileSymbolTable symbolTable;
    private FileSymbolTable fileTable;
    private FileSymbolTable variableTable;
    private boolean cancelled;
    private TeaVMProgressListener progressListener;
    private TeaVM vm;
    private boolean fastDependencyAnalysis;
    private TeaVMOptimizationLevel optimizationLevel = TeaVMOptimizationLevel.SIMPLE;
    private List<SourceFileProvider> sourceFileProviders = new ArrayList<>();
    private DebugInformationBuilder debugEmitter;
    private JavaScriptTarget javaScriptTarget;
    private WasmTarget webAssemblyTarget;
    private WasmBinaryVersion wasmVersion = WasmBinaryVersion.V_0x1;
    private WasmDebugInfoLocation wasmDebugInfoLocation = WasmDebugInfoLocation.EXTERNAL;
    private WasmDebugInfoLevel wasmDebugInfoLevel = WasmDebugInfoLevel.DEOBFUSCATION;
    private boolean wasmExceptionsUsed;
    private CTarget cTarget;
    private Set<File> generatedFiles = new HashSet<>();
    private int minHeapSize = 4 * (1 << 20);
    private int maxHeapSize = 128 * (1 << 20);
    private int minDirectBuffersSize = 2 * (1 << 20);
    private int maxDirectBuffersSize = 32 * (1 << 20);
    private boolean importedWasmMemory;
    private ReferenceCache referenceCache;
    private boolean heapDump;
    private boolean shortFileNames;
    private boolean assertionsRemoved;
    private SourceMapBuilder wasmSourceMapWriter;

    public File getTargetDirectory() {
        return targetDirectory;
    }

    public void setTargetDirectory(File targetDirectory) {
        this.targetDirectory = targetDirectory;
    }

    public void setTargetFileName(String targetFileName) {
        this.targetFileName = targetFileName;
    }

    public void setObfuscated(boolean obfuscated) {
        this.obfuscated = obfuscated;
    }

    public void setJsModuleType(JSModuleType jsModuleType) {
        this.jsModuleType = jsModuleType;
    }

    public void setStrict(boolean strict) {
        this.strict = strict;
    }

    public void setMaxTopLevelNames(int maxTopLevelNames) {
        this.maxTopLevelNames = maxTopLevelNames;
    }

    public boolean isIncremental() {
        return incremental;
    }

    public void setIncremental(boolean incremental) {
        this.incremental = incremental;
    }

    public String getMainClass() {
        return mainClass;
    }

    public void setMainClass(String mainClass) {
        this.mainClass = mainClass;
    }

    public void setEntryPointName(String entryPointName) {
        this.entryPointName = entryPointName;
    }

    public boolean isDebugInformationGenerated() {
        return debugInformationGenerated;
    }

    public void setDebugInformationGenerated(boolean debugInformationGenerated) {
        this.debugInformationGenerated = debugInformationGenerated;
    }

    public File getCacheDirectory() {
        return cacheDirectory;
    }

    public void setCacheDirectory(File cacheDirectory) {
        this.cacheDirectory = cacheDirectory;
    }

    public boolean isSourceMapsFileGenerated() {
        return sourceMapsFileGenerated;
    }

    public void setSourceMapsFileGenerated(boolean sourceMapsFileGenerated) {
        this.sourceMapsFileGenerated = sourceMapsFileGenerated;
    }

    @Deprecated
    public boolean isSourceFilesCopied() {
        return sourceFilePolicy == TeaVMSourceFilePolicy.COPY;
    }

    @Deprecated
    public void setSourceFilesCopied(boolean sourceFilesCopied) {
        if (isSourceFilesCopied() == sourceFilesCopied) {
            return;
        }
        sourceFilePolicy = sourceFilesCopied
                ? TeaVMSourceFilePolicy.COPY
                : TeaVMSourceFilePolicy.DO_NOTHING;
    }

    public void setSourceFilePolicy(TeaVMSourceFilePolicy sourceFilePolicy) {
        this.sourceFilePolicy = sourceFilePolicy;
    }

    public Properties getProperties() {
        return properties;
    }

    public List<String> getTransformers() {
        return transformers;
    }

    public List<String> getClassesToPreserve() {
        return classesToPreserve;
    }

    public TeaVMToolLog getLog() {
        return log;
    }

    public void setLog(TeaVMToolLog log) {
        this.log = log;
    }

    public TeaVMTargetType getTargetType() {
        return targetType;
    }

    public void setTargetType(TeaVMTargetType targetType) {
        this.targetType = targetType;
    }

    public TeaVMOptimizationLevel getOptimizationLevel() {
        return optimizationLevel;
    }

    public void setOptimizationLevel(TeaVMOptimizationLevel optimizationLevel) {
        this.optimizationLevel = optimizationLevel;
    }

    public boolean isFastDependencyAnalysis() {
        return fastDependencyAnalysis;
    }

    public void setFastDependencyAnalysis(boolean fastDependencyAnalysis) {
        this.fastDependencyAnalysis = fastDependencyAnalysis;
    }

    public void setMinHeapSize(int minHeapSize) {
        this.minHeapSize = minHeapSize;
    }

    public void setMaxHeapSize(int maxHeapSize) {
        this.maxHeapSize = maxHeapSize;
    }

    public void setMinDirectBuffersSize(int minDirectBuffersSize) {
        this.minDirectBuffersSize = minDirectBuffersSize;
    }

    public void setMaxDirectBuffersSize(int maxDirectBuffersSize) {
        this.maxDirectBuffersSize = maxDirectBuffersSize;
    }

    public void setImportedWasmMemory(boolean importedWasmMemory) {
        this.importedWasmMemory = importedWasmMemory;
    }

    public ClassLoader getClassLoader() {
        return classLoader;
    }

    public void setClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public void setClassPath(List<File> classPath) {
        this.classPath = classPath;
    }

    public WasmBinaryVersion getWasmVersion() {
        return wasmVersion;
    }

    public void setWasmVersion(WasmBinaryVersion wasmVersion) {
        this.wasmVersion = wasmVersion;
    }

    public void setWasmExceptionsUsed(boolean wasmExceptionsUsed) {
        this.wasmExceptionsUsed = wasmExceptionsUsed;
    }

    public void setWasmDebugInfoLocation(WasmDebugInfoLocation wasmDebugInfoLocation) {
        this.wasmDebugInfoLocation = wasmDebugInfoLocation;
    }

    public void setWasmDebugInfoLevel(WasmDebugInfoLevel wasmDebugInfoLevel) {
        this.wasmDebugInfoLevel = wasmDebugInfoLevel;
    }

    public void setHeapDump(boolean heapDump) {
        this.heapDump = heapDump;
    }

    public void setShortFileNames(boolean shortFileNames) {
        this.shortFileNames = shortFileNames;
    }

    public void setAssertionsRemoved(boolean assertionsRemoved) {
        this.assertionsRemoved = assertionsRemoved;
    }

    public void setProgressListener(TeaVMProgressListener progressListener) {
        this.progressListener = progressListener;
    }

    public boolean wasCancelled() {
        return cancelled;
    }

    public ProblemProvider getProblemProvider() {
        return vm != null ? vm.getProblemProvider() : null;
    }

    public DependencyInfo getDependencyInfo() {
        return vm.getDependencyInfo();
    }

    public Collection<String> getClasses() {
        return vm != null ? vm.getClasses() : Collections.emptyList();
    }

    public Set<File> getGeneratedFiles() {
        return generatedFiles;
    }

    public Collection<String> getUsedResources() {
        if (vm == null) {
            return Collections.emptyList();
        }

        return InstructionLocationReader.extractUsedResources(vm);
    }

    public void addSourceFileProvider(SourceFileProvider sourceFileProvider) {
        sourceFileProviders.add(sourceFileProvider);
    }

    private TeaVMTarget prepareTarget() {
        switch (targetType) {
            case JAVASCRIPT:
                return prepareJavaScriptTarget();
            case WEBASSEMBLY:
                return prepareWebAssemblyDefaultTarget();
            case WEBASSEMBLY_WASI:
                return prepareWebAssemblyWasiTarget();
            case WEBASSEMBLY_GC:
                return prepareWebAssemblyGCTarget();
            case C:
                return prepareCTarget();
        }
        throw new IllegalStateException("Unknown target type: " + targetType);
    }

    private TeaVMTarget prepareJavaScriptTarget() {
        javaScriptTarget = new JavaScriptTarget();
        javaScriptTarget.setObfuscated(obfuscated);
        javaScriptTarget.setStrict(strict);
        javaScriptTarget.setMaxTopLevelNames(maxTopLevelNames);

        debugEmitter = debugInformationGenerated || sourceMapsFileGenerated
                ? new DebugInformationBuilder(referenceCache) : null;
        javaScriptTarget.setDebugEmitter(debugEmitter);
        javaScriptTarget.setModuleType(jsModuleType);

        return javaScriptTarget;
    }

    private WasmTarget prepareWebAssemblyTarget() {
        webAssemblyTarget = new WasmTarget();
        webAssemblyTarget.setDebugging(debugInformationGenerated);
        webAssemblyTarget.setCEmitted(debugInformationGenerated);
        webAssemblyTarget.setWastEmitted(debugInformationGenerated);
        webAssemblyTarget.setVersion(wasmVersion);
        webAssemblyTarget.setMinHeapSize(minHeapSize);
        webAssemblyTarget.setMaxHeapSize(maxHeapSize);
        webAssemblyTarget.setObfuscated(obfuscated);
        webAssemblyTarget.setExceptionsUsed(wasmExceptionsUsed);
        return webAssemblyTarget;
    }

    private WasmTarget prepareWebAssemblyDefaultTarget() {
        WasmTarget target = prepareWebAssemblyTarget();
        target.setRuntimeType(WasmRuntimeType.TEAVM);
        return target;
    }

    private WasmTarget prepareWebAssemblyWasiTarget() {
        WasmTarget target = prepareWebAssemblyTarget();
        target.setRuntimeType(WasmRuntimeType.WASI);
        return target;
    }

    private WasmGCTarget prepareWebAssemblyGCTarget() {
        var target = new WasmGCTarget();
        target.setObfuscated(obfuscated);
        target.setStrict(strict);
        target.setDebugInfo(debugInformationGenerated);
        target.setDebugInfoLevel(debugInformationGenerated ? WasmDebugInfoLevel.FULL : wasmDebugInfoLevel);
        target.setDebugInfoLocation(wasmDebugInfoLocation);
        target.setBufferHeapMinSize(minDirectBuffersSize);
        target.setBufferHeapMaxSize(maxDirectBuffersSize);
        target.setImportedMemory(importedWasmMemory);
        if (sourceMapsFileGenerated) {
            wasmSourceMapWriter = new SourceMapBuilder();
            target.setSourceMapBuilder(wasmSourceMapWriter);
            target.setSourceMapLocation(getResolvedTargetFileName() + ".map");
        }
        return target;
    }

    private CTarget prepareCTarget() {
        cTarget = new CTarget(new CNameProvider());
        cTarget.setMinHeapSize(minHeapSize);
        cTarget.setMaxHeapSize(maxHeapSize);
        cTarget.setLineNumbersGenerated(debugInformationGenerated);
        cTarget.setHeapDump(heapDump);
        cTarget.setObfuscated(obfuscated);
        cTarget.setFileNames(shortFileNames
                ? new ShorteningFileNameProvider(new SimpleFileNameProvider())
                : new SimpleFileNameProvider());
        return cTarget;
    }

    public void generate() throws TeaVMToolException {
        try (var resourceProvider = createResourceProvider()) {
            cancelled = false;
            log.info("Running TeaVM");
            referenceCache = new ReferenceCache();
            TeaVMBuilder vmBuilder = new TeaVMBuilder(prepareTarget());
            CacheStatus cacheStatus;
            vmBuilder.setReferenceCache(referenceCache);
            if (incremental) {
                cacheDirectory.mkdirs();
                symbolTable = new FileSymbolTable(new File(cacheDirectory, "symbols"));
                fileTable = new FileSymbolTable(new File(cacheDirectory, "files"));
                variableTable = new FileSymbolTable(new File(cacheDirectory, "variables"));
                ClasspathClassHolderSource innerClassSource = new ClasspathClassHolderSource(resourceProvider,
                        referenceCache);
                ClassHolderSource classSource = new PreOptimizingClassHolderSource(innerClassSource);
                cachedClassSource = new DiskCachedClassReaderSource(cacheDirectory, referenceCache, symbolTable,
                        fileTable, variableTable, classSource, innerClassSource);
                programCache = new DiskProgramCache(cacheDirectory, referenceCache, symbolTable, fileTable,
                        variableTable);
                if (targetType == TeaVMTargetType.JAVASCRIPT) {
                    astCache = new DiskMethodNodeCache(cacheDirectory, referenceCache, symbolTable, fileTable,
                            variableTable);
                    javaScriptTarget.setAstCache(astCache);
                }
                try {
                    symbolTable.update();
                    fileTable.update();
                    variableTable.update();
                } catch (IOException e) {
                    log.info("Cache is missing");
                }
                vmBuilder.setClassLoader(classLoader).setClassSource(cachedClassSource);
                cacheStatus = cachedClassSource;
            } else {
                vmBuilder.setClassLoader(classLoader).setClassSource(new PreOptimizingClassHolderSource(
                        new ClasspathClassHolderSource(resourceProvider, referenceCache)));
                cacheStatus = AlwaysStaleCacheStatus.INSTANCE;
            }

            vmBuilder.setResourceProvider(resourceProvider);
            vmBuilder.setDependencyAnalyzerFactory(fastDependencyAnalysis
                    ? FastDependencyAnalyzer::new
                    : PreciseDependencyAnalyzer::new);
            vmBuilder.setObfuscated(obfuscated);
            vmBuilder.setStrict(strict);

            vm = vmBuilder.build();
            if (progressListener != null) {
                vm.setProgressListener(progressListener);
            }

            if (assertionsRemoved) {
                vm.add(new AssertionRemoval());
            }

            vm.setProperties(properties);
            vm.setProgramCache(incremental ? programCache : EmptyProgramCache.INSTANCE);
            vm.setCacheStatus(cacheStatus);
            vm.setOptimizationLevel(!fastDependencyAnalysis && !incremental
                    ? optimizationLevel
                    : TeaVMOptimizationLevel.SIMPLE);
            if (incremental) {
                vm.addVirtualMethods(m -> true);
            }

            vm.installPlugins();
            for (ClassHolderTransformer transformer : resolveTransformers()) {
                vm.add(transformer);
            }
            vm.setEntryPoint(mainClass);
            if (entryPointName != null) {
                vm.setEntryPointName(entryPointName);
            }
            for (String className : classesToPreserve) {
                vm.preserveType(className);
            }

            if (!targetDirectory.exists() && !targetDirectory.mkdirs()) {
                log.error("Target directory could not be created");
                System.exit(-1);
            }

            BuildTarget buildTarget = new DirectoryBuildTarget(targetDirectory);
            String outputName = getResolvedTargetFileName();
            vm.build(buildTarget, outputName);
            if (vm.wasCancelled()) {
                log.info("Build cancelled");
                cancelled = true;
                return;
            }

            ProblemProvider problemProvider = vm.getProblemProvider();
            if (problemProvider.getProblems().isEmpty()) {
                log.info("Output file successfully built");
            } else if (problemProvider.getSevereProblems().isEmpty()) {
                log.info("Output file built with warnings");
            } else {
                log.info("Output file built with errors");
            }

            File outputFile = new File(targetDirectory, outputName);
            generatedFiles.add(outputFile);

            if (targetType == TeaVMTargetType.JAVASCRIPT) {
                try (OutputStream output = new FileOutputStream(outputFile, true);
                        Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
                    additionalJavaScriptOutput(writer);
                }
            } else if (targetType == TeaVMTargetType.WEBASSEMBLY_GC) {
                additionalWasmGCOutput();
            }

            if (incremental) {
                programCache.flush();
                if (astCache != null) {
                    astCache.flush();
                }
                cachedClassSource.flush();
                symbolTable.flush();
                fileTable.flush();
                variableTable.flush();
                log.info("Cache updated");
            }

            printStats();
        } catch (IOException e) {
            throw new TeaVMToolException("IO error occurred", e);
        }
    }

    private ResourceProvider createResourceProvider() {
        return classPath != null
                ? ResourceProvider.ofClassPath(classPath)
                : new ClasspathResourceProvider(classLoader);
    }

    private String getResolvedTargetFileName() {
        if (targetFileName.isEmpty()) {
            switch (targetType) {
                case JAVASCRIPT:
                    return "classes.js";
                case WEBASSEMBLY:
                case WEBASSEMBLY_WASI:
                case WEBASSEMBLY_GC:
                    return "classes.wasm";
                case C:
                    return "classes.c";
                default:
                    return "classes";
            }
        }
        return targetFileName;
    }

    private void additionalJavaScriptOutput(Writer writer) throws IOException {
        if (debugInformationGenerated) {
            assert debugEmitter != null;
            DebugInformation debugInfo = debugEmitter.getDebugInformation();
            File debugSymbolFile = new File(targetDirectory, getResolvedTargetFileName() + ".teavmdbg");
            try (OutputStream debugInfoOut = new BufferedOutputStream(new FileOutputStream(debugSymbolFile))) {
                debugInfo.write(debugInfoOut);
            }
            generatedFiles.add(debugSymbolFile);
            log.info("Debug information successfully written");
        }
        if (sourceMapsFileGenerated) {
            assert debugEmitter != null;
            DebugInformation debugInfo = debugEmitter.getDebugInformation();
            String sourceMapsFileName = getResolvedTargetFileName() + ".map";
            writer.append("\n//# sourceMappingURL=").append(sourceMapsFileName);
            File sourceMapsFile = new File(targetDirectory, sourceMapsFileName);
            try (Writer sourceMapsOut = new OutputStreamWriter(new FileOutputStream(sourceMapsFile),
                    StandardCharsets.UTF_8)) {
                writeSourceMaps(sourceMapsOut, debugInfo);
            }
            generatedFiles.add(sourceMapsFile);
            log.info("Source maps successfully written");
        }
    }

    private void additionalWasmGCOutput() throws IOException {
        if (sourceMapsFileGenerated) {
            var targetDir = new File(targetDirectory, "src");
            var resolver = new DefaultSourceFileResolver(targetDir, sourceFileProviders);
            resolver.setSourceFilePolicy(sourceFilePolicy);
            resolver.open();

            if (sourceFilePolicy != TeaVMSourceFilePolicy.DO_NOTHING) {
                wasmSourceMapWriter.addSourceResolver(resolver);
            }
            var file = new File(targetDirectory, getResolvedTargetFileName() + ".map");
            try (var out = new FileOutputStream(file);
                    var writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                wasmSourceMapWriter.writeSourceMap(writer);
            }

            resolver.close();
        }
    }

    private void writeSourceMaps(Writer out, DebugInformation debugInfo) throws IOException {
        var sourceMapWriter = new SourceMapsWriter(out);
        var targetDir = new File(targetDirectory, "src");
        var resolver = new DefaultSourceFileResolver(targetDir, sourceFileProviders);
        resolver.setSourceFilePolicy(sourceFilePolicy);
        resolver.open();

        if (sourceFilePolicy != TeaVMSourceFilePolicy.DO_NOTHING) {
            sourceMapWriter.addSourceResolver(resolver);
        }
        sourceMapWriter.write(getResolvedTargetFileName(), "src", debugInfo);

        resolver.close();
    }

    private void printStats() {
        if (vm == null || vm.getWrittenClasses() == null) {
            return;
        }

        int classCount = vm.getWrittenClasses().getClassNames().size();
        int methodCount = 0;
        for (String className : vm.getWrittenClasses().getClassNames()) {
            ClassReader cls = vm.getWrittenClasses().get(className);
            methodCount += cls.getMethods().size();
        }

        log.info("Classes compiled: " + classCount);
        log.info("Methods compiled: " + methodCount);
    }

    private List<ClassHolderTransformer> resolveTransformers() {
        List<ClassHolderTransformer> transformerInstances = new ArrayList<>();
        if (transformers == null) {
            return transformerInstances;
        }
        for (String transformerName : transformers) {
            Class<?> transformerRawType;
            try {
                transformerRawType = Class.forName(transformerName, true, classLoader);
            } catch (ClassNotFoundException e) {
                log.error("Transformer not found: " + transformerName, e);
                continue;
            }
            if (!ClassHolderTransformer.class.isAssignableFrom(transformerRawType)) {
                log.error("Transformer " + transformerName + " is not subtype of "
                        + ClassHolderTransformer.class.getName());
                continue;
            }
            Class<? extends ClassHolderTransformer> transformerType = transformerRawType.asSubclass(
                    ClassHolderTransformer.class);
            Constructor<? extends ClassHolderTransformer> ctor;
            try {
                ctor = transformerType.getConstructor();
            } catch (NoSuchMethodException e) {
                log.error("Transformer " + transformerName + " has no default constructor");
                continue;
            }
            try {
                ClassHolderTransformer transformer = ctor.newInstance();
                transformerInstances.add(transformer);
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                log.error("Error instantiating transformer " + transformerName, e);
            }
        }
        return transformerInstances;
    }
}
