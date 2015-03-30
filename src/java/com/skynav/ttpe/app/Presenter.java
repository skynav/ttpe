/*
 * Copyright 2014-15 Skynav, Inc. All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY SKYNAV, INC. AND ITS CONTRIBUTORS “AS IS” AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL SKYNAV, INC. OR ITS CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.skynav.ttpe.app;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.Charset;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.xml.namespace.QName;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;

import org.xml.sax.InputSource;

import com.skynav.ttpe.layout.LayoutProcessor;
import com.skynav.ttpe.render.DocumentFrame;
import com.skynav.ttpe.render.ImageFrame;
import com.skynav.ttpe.render.Frame;
import com.skynav.ttpe.render.FrameImage;
import com.skynav.ttpe.render.RenderProcessor;
import com.skynav.ttv.app.InvalidOptionUsageException;
import com.skynav.ttv.app.MissingOptionArgumentException;
import com.skynav.ttv.app.OptionSpecification;
import com.skynav.ttx.app.TimedTextTransformer;
import com.skynav.ttv.util.IOUtil;
import com.skynav.ttv.util.Reporter;
import com.skynav.ttv.util.TextTransformer;
import com.skynav.ttx.transformer.Transformers;
import com.skynav.ttx.transformer.TransformerContext;
import com.skynav.ttx.transformer.TransformerOptions;

/* Suppressing PMD warnings for the time being.
 * This should be reviewed after Beta-deliverable is met at the end of
 * April 2015 */
@SuppressWarnings("PMD")
public class Presenter extends TimedTextTransformer {

    // banner text
    private static final String title = "Timed Text Presentation Engine (TTPE) [" + Version.CURRENT + "]";
    private static final String copyright = "Copyright 2013-15 Skynav, Inc.";
    private static final String banner = title + " " + copyright;

    // option and usage info
    private static final String[][] shortOptionSpecifications = new String[][] {
    };
    private static final Collection<OptionSpecification> shortOptions;
    static {
        shortOptions = new java.util.TreeSet<OptionSpecification>();
        for (String[] spec : shortOptionSpecifications) {
            shortOptions.add(new OptionSpecification(spec[0], spec[1]));
        }
    }

    private static final String DEFAULT_OUTPUT_ENCODING         = "utf-8";
    private static Charset defaultOutputEncoding;
    private static final String defaultOutputFileNamePattern    = "ttpe-{0,number,0000}.dat";

    static {
        try {
            defaultOutputEncoding = Charset.forName(DEFAULT_OUTPUT_ENCODING);
        } catch (RuntimeException e) {
            defaultOutputEncoding = Charset.defaultCharset();
        }
    }

    private static final String[][] longOptionSpecifications = new String[][] {
        { "layout",                     "NAME",     "specify layout name (default: " + LayoutProcessor.getDefaultName() + ")" },
        { "output-archive",             "",         "combine output frames into frames archive file" },
        { "output-archive-file",        "NAME",     "specify path of frames archive file" },
        { "output-directory",           "DIRECTORY","specify path to directory where output is to be written" },
        { "output-encoding",            "ENCODING", "specify character encoding of output (default: " + defaultOutputEncoding.name() + ")" },
        { "output-format",              "NAME",     "specify output format name (default: " + RenderProcessor.getDefaultName() + ")" },
        { "output-indent",              "",         "indent output (default: no indent)" },
        { "output-pattern",             "PATTERN",  "specify output file name pattern" },
        { "output-retain-frames",       "",         "retain individual frame files after archiving" },
        { "show-formats",               "",         "show output formats" },
        { "show-layouts",               "",         "show built-in layouts" },
        { "show-memory",                "",         "show memory statistics" },
    };
    private static final Collection<OptionSpecification> longOptions;
    static {
        longOptions = new java.util.TreeSet<OptionSpecification>();
        for (String[] spec : longOptionSpecifications) {
            longOptions.add(new OptionSpecification(spec[0], spec[1], spec[2]));
        }
    }

    // uri related constants
    private static final String uriFileDescriptorScheme         = "fd";
    private static final String uriFileDescriptorStandardOut    = "stdout";
    private static final String uriStandardOutput               = uriFileDescriptorScheme + ":" + uriFileDescriptorStandardOut;
    private static final String uriFileScheme                   = "file";

    // defaults state
    private LayoutProcessor defaultLayout;
    private RenderProcessor defaultRenderer;

    // options state
    private boolean outputArchive;
    private String outputArchiveFilePath;
    private String outputDirectoryPath;
    private String outputEncodingName;
    private boolean outputIndent;
    private String outputPattern;
    private boolean outputRetainFrames;
    private boolean showLayouts;
    private boolean showRenderers;
    private boolean showMemory;

    // derived option state
    private LayoutProcessor layout;
    private File outputArchiveFile;
    private File outputDirectory;
    private Charset outputEncoding;
    private RenderProcessor renderer;

    // processing state
    private int outputFileSequence;

    public Presenter() {
        this.defaultLayout = LayoutProcessor.getDefaultProcessor(this);
        this.defaultRenderer = RenderProcessor.getDefaultProcessor(this);
    }

    protected String getOutputPattern() {
        return outputPattern;
    }

    @Override
    public void processResult(String[] args, URI uri, Object root) {
        super.processResult(args, uri, root);
        performPresentation(args, uri, root, extractResourceState(TransformerContext.ResourceState.ttxOutput.name()));
    }

    @Override
    protected void initializeResourceState(URI uri) {
        super.initializeResourceState(uri);
        setResourceState(TransformerContext.ResourceState.ttxSuppressOutputSerialization.name(), Boolean.TRUE);
        setResourceState(TransformerContext.ResourceState.ttxRetainLocations.name(), Boolean.FALSE);
    }

    @Override
    public Object getResourceState(String key) {
        if (key == TransformerContext.ResourceState.ttxTransformer.name())
            return Transformers.getTransformer("isd");
        else
            return super.getResourceState(key);
    }

    @Override
    public String[] preProcessOptions(String[] args, Collection<OptionSpecification> baseShortOptions, Collection<OptionSpecification> baseLongOptions) {
        LayoutProcessor layout = null;
        RenderProcessor renderer = null;
        for (int i = 0; i < args.length; ++i) {
            String arg = args[i];
            if (arg.indexOf("--") == 0) {
                String option = arg.substring(2);
                if (option.equals("layout")) {
                    if (i + 1 <= args.length) {
                        String layoutName = args[++i];
                        layout = LayoutProcessor.getProcessor(layoutName, this);
                        if (layout == null)
                            throw new InvalidOptionUsageException("layout", "unknown layout: " + layoutName);
                    }
                } else if (option.equals("output-format")) {
                    if (i + 1 <= args.length) {
                        String rendererName = args[++i];
                        renderer = RenderProcessor.getProcessor(rendererName, this);
                        if (renderer == null)
                            throw new InvalidOptionUsageException("output-format", "unknown format: " + rendererName);
                    }
                }
            }
        }
        if (layout == null)
            layout = defaultLayout;
        if (renderer == null)
            renderer = defaultRenderer;
        this.layout = layout;
        this.renderer = renderer;
        TransformerOptions[] transformerOptions = new TransformerOptions[] { layout, renderer };
        populateMergedOptionsMaps(baseShortOptions, baseLongOptions, transformerOptions, shortOptions, longOptions);
        return args;
    }

    @Override
    public void showBanner(PrintWriter out) {
        showBanner(out, banner);
    }

    @Override
    public void runOptions(PrintWriter out) {
        if (showLayouts)
            showLayouts(out);
        if (showRenderers)
            showRenderers(out);
    }

    @Override
    protected boolean doMergeTransformerOptions() {
        return false;
    }

    @Override
    protected int parseLongOption(String args[], int index) {
        String option = args[index];
        assert option.length() > 2;
        option = option.substring(2);
        if (option.equals("layout")) {
            if (index + 1 > args.length)
                throw new MissingOptionArgumentException("--" + option);
            else // handled by preProcessOptions
                ++index;
        } else if (option.equals("output-archive")) {
            outputArchive = true;
        } else if (option.equals("output-archive-file")) {
            if (index + 1 > args.length)
                throw new MissingOptionArgumentException("--" + option);
            outputArchiveFilePath = args[++index];
        } else if (option.equals("output-directory")) {
            if (index + 1 > args.length)
                throw new MissingOptionArgumentException("--" + option);
            outputDirectoryPath = args[++index];
        } else if (option.equals("output-encoding")) {
            if (index + 1 > args.length)
                throw new MissingOptionArgumentException("--" + option);
            outputEncodingName = args[++index];
        } else if (option.equals("output-format")) {
            if (index + 1 > args.length)
                throw new MissingOptionArgumentException("--" + option);
            else // handled by preProcessOptions
                ++index;
        } else if (option.equals("output-indent")) {
            outputIndent = true;
        } else if (option.equals("output-pattern")) {
            if (index + 1 > args.length)
                throw new MissingOptionArgumentException("--" + option);
            outputPattern = args[++index];
        } else if (option.equals("output-retain-frames")) {
            outputRetainFrames = true;
        } else if (option.equals("show-formats")) {
            showRenderers = true;
        } else if (option.equals("show-layouts")) {
            showLayouts = true;
        } else if (option.equals("show-memory")) {
            showMemory = true;
        } else {
            return super.parseLongOption(args, index);
        }
        return index + 1;
    }

    @Override
    public void processDerivedOptions() {
        // first handle ttx derived options, then layout, then renderer
        super.processDerivedOptions();
        assert layout != null;
        layout.processDerivedOptions();
        assert renderer != null;
        renderer.processDerivedOptions();
        // output archive file
        File outputArchiveFile;
        if (outputArchive && (outputArchiveFilePath != null)) {
            outputArchiveFile = new File(outputArchiveFilePath);
            if (!outputArchiveFile.getParentFile().exists())
                throw new InvalidOptionUsageException("output-archive-file", "directory does not exist: " + outputArchiveFilePath);
        } else
            outputArchiveFile = null;
        this.outputArchiveFile = outputArchiveFile;
        // output directory
        File outputDirectory;
        if (outputDirectoryPath != null) {
            outputDirectory = new File(outputDirectoryPath);
            if (!outputDirectory.exists())
                throw new InvalidOptionUsageException("output-directory", "directory does not exist: " + outputDirectoryPath);
            else if (!outputDirectory.isDirectory())
                throw new InvalidOptionUsageException("output-directory", "not a directory: " + outputDirectoryPath);
        } else
            outputDirectory = new File(".");
        this.outputDirectory = outputDirectory;
        // output encoding
        Charset outputEncoding;
        if (outputEncodingName != null) {
            try {
                outputEncoding = Charset.forName(outputEncodingName);
            } catch (Exception e) {
                outputEncoding = null;
            }
            if (outputEncoding == null)
                throw new InvalidOptionUsageException("output-encoding", "unknown encoding: " + outputEncodingName);
        } else
            outputEncoding = null;
        if (outputEncoding == null)
            outputEncoding = defaultOutputEncoding;
        this.outputEncoding = outputEncoding;
        // output pattern
        String outputPattern = renderer.getOutputPattern();
        if (outputPattern == null)
            outputPattern = this.outputPattern;
        if (outputPattern == null)
            outputPattern = defaultOutputFileNamePattern;
        this.outputPattern = outputPattern;
        // show memory
        setShowMemory(showMemory);
    }


    private void showLayouts(PrintWriter out) {
        String defaultLayoutName = defaultLayout.getName();
        StringBuffer sb = new StringBuffer();
        sb.append("Layouts:\n");
        for (String layoutName : LayoutProcessor.getProcessorNames()) {
            sb.append("  ");
            sb.append(layoutName);
            if (layoutName.equals(defaultLayoutName)) {
                sb.append(" (default)");
            }
            sb.append('\n');
        }
        out.print(sb.toString());
    }

    private void showRenderers(PrintWriter out) {
        String defaultRendererName = defaultRenderer.getName();
        StringBuffer sb = new StringBuffer();
        sb.append("Formats:\n");
        for (String rendererName : RenderProcessor.getProcessorNames()) {
            sb.append("  ");
            sb.append(rendererName);
            if (rendererName.equals(defaultRendererName)) {
                sb.append(" (default)");
            }
            sb.append('\n');
        }
        out.print(sb.toString());
    }

    private void performPresentation(String[] args, URI uri, Object root, Object ttxOutput) {
        Reporter reporter = getReporter();
        long prePresentMemory = 0;
        long postPresentMemory = 0;
        if (showMemory) {
            prePresentMemory = getUsedMemory();
            reporter.logInfo(reporter.message("*KEY*", "Pre-presentation memory usage: {0}", prePresentMemory));
        }
        LayoutProcessor lp = this.layout;
        assert lp != null;
        RenderProcessor rp = this.renderer;
        assert rp != null;
        this.outputFileSequence = 0;
        List<Frame> frames = new java.util.ArrayList<Frame>();
        if (ttxOutput instanceof List<?>) {
            List<?> isdSequence = (List<?>) ttxOutput;
            while (!isdSequence.isEmpty()) {
                Object isd = isdSequence.remove(0);
                Document doc = readISD(isd);
                if (doc != null) {
                    long preRenderMemory = 0;
                    long postRenderMemory = 0;
                    if (showMemory) {
                        preRenderMemory = getUsedMemory();
                        reporter.logInfo(reporter.message("*KEY*", "Pre-render memory usage: {0}", preRenderMemory));
                    }
                    List<Frame> documentFrames = rp.render(lp.layout((Document) doc));
                    if (showMemory) {
                        postRenderMemory = getUsedMemory();
                        reporter.logInfo(reporter.message("*KEY*", "Post-render memory usage: {0}, delta: {1}", postRenderMemory, postRenderMemory - preRenderMemory));
                    }
                    long preWriteMemory = 0;
                    long postWriteMemory = 0;
                    if (showMemory) {
                        preWriteMemory = getUsedMemory();
                        reporter.logInfo(reporter.message("*KEY*", "Pre-write memory usage: {0}", preWriteMemory));
                    }
                    while (!documentFrames.isEmpty()) {
                        Frame f = documentFrames.remove(0);
                        if (writeFrame(uri, f))
                            frames.add(f);
                    }
                    if (showMemory) {
                        postWriteMemory = getUsedMemory();
                        reporter.logInfo(reporter.message("*KEY*", "Post-write memory usage: {0}, delta: {1}", postWriteMemory, postWriteMemory - preWriteMemory));
                    }
                }
                rp.clear(false);
                lp.clear(false);
            }
            rp.clear(true);
            lp.clear(true);
        }
        if (outputArchive)
            archiveFrames(uri, frames, outputArchiveFile);
        if (outputArchive && !outputRetainFrames)
            removeFrameFiles(frames);
        if (showMemory) {
            postPresentMemory = getUsedMemory();
            reporter.logInfo(reporter.message("*KEY*", "Post-presentation memory usage: {0}, delta: {1}", postPresentMemory, postPresentMemory - prePresentMemory));
        }
    }

    private Document readISD(Object isd) {
        if (isd instanceof File)
            return readISDAsFile((File) isd);
        else if (isd instanceof byte[])
            return readISDAsByteArray((byte[]) isd);
        else
            return null;
    }

    private Document readISDAsFile(File data) {
        FileInputStream fis = null;
        BufferedInputStream bis = null;
        try {
            fis = new FileInputStream(data);
            bis = new BufferedInputStream(fis);
            return readISDFromStream(bis);
        } catch (IOException e) {
            getReporter().logError(e);
            return null;
        } finally {
            IOUtil.closeSafely(bis);
            IOUtil.closeSafely(fis);
        }
    }

    private Document readISDAsByteArray(byte[] data) {
        ByteArrayInputStream bas = null;
        BufferedInputStream bis = null;
        try {
            bas = new ByteArrayInputStream(data);
            bis = new BufferedInputStream(bas);
            return readISDFromStream(bis);
        } finally {
            IOUtil.closeSafely(bis);
            IOUtil.closeSafely(bas);
        }
    }

    private Document readISDFromStream(InputStream is) {
        try {
            SAXSource source = new SAXSource(new InputSource(is));
            DOMResult result = new DOMResult();
            TransformerFactory.newInstance().newTransformer().transform(source, result);
            return (Document) result.getNode();
        } catch (TransformerFactoryConfigurationError e) {
            getReporter().logError(new Exception(e));
        } catch (TransformerException e) {
            getReporter().logError(e);
        }
        return null;
    }


    private boolean writeFrame(URI uri, Frame f) {
        if (f instanceof DocumentFrame)
            return writeDocumentFrame(uri, (DocumentFrame) f);
        else if (f instanceof ImageFrame)
            return writeImageFrame(uri, (ImageFrame) f);
        else
            throw new UnsupportedOperationException();
    }

    private boolean writeDocumentFrame(URI uri, DocumentFrame f) {
        boolean fail = false;
        Document d = f.getDocument();
        Reporter reporter = getReporter();
        Map<String,String> prefixes = f.getPrefixes();
        Set<QName> startTagExclusions = f.getStartExclusions();
        Set<QName> endTagExclusions = f.getEndExclusions();
        BufferedOutputStream bos = null;
        BufferedWriter bw = null;
        try {
            DOMSource source = new DOMSource(d);
            File[] retOutputFile = new File[1];
            if ((bos = getFrameOutputStream(uri, retOutputFile)) != null) {
                bw = new BufferedWriter(new OutputStreamWriter(bos, outputEncoding));
                StreamResult result = new StreamResult(bw);
                Transformer t = new TextTransformer(outputEncoding.name(), outputIndent, prefixes, startTagExclusions, endTagExclusions);
                t.transform(source, result);
                File outputFile = retOutputFile[0];
                reporter.logInfo(reporter.message("*KEY*", "Wrote TTPE artifact ''{0}''.", (outputFile != null) ? outputFile.getAbsolutePath() : uriStandardOutput));
                f.setFile(outputFile);
            }
        } catch (Exception e) {
            reporter.logError(e);
        } finally {
            if (bw != null) {
                try { bw.close(); } catch (IOException e) {}
            }
            IOUtil.closeSafely(bos);
            f.clearDocument(); // enable GC on frame's document
        }
        return !fail && (reporter.getResourceErrors() == 0);
    }

    private boolean writeImageFrame(URI uri, ImageFrame f) {
        boolean fail = false;
        Reporter reporter = getReporter();
        if (f.hasImages()) {
            for (FrameImage i : f.getImages()) {
                if (!writeFrameImage(uri, i))
                    fail = true;
            }
        }
        return !fail && (reporter.getResourceErrors() == 0);
    }

    private boolean writeFrameImage(URI uri, FrameImage i) {
        boolean fail = false;
        Reporter reporter = getReporter();
        BufferedOutputStream bos = null;
        try {
            byte[] data = i.getData();
            File[] retOutputFile = new File[1];
            if ((bos = getFrameOutputStream(uri, retOutputFile)) != null) {
                bos.write(data);
                File outputFile = retOutputFile[0];
                bos.close(); bos = null;
                reporter.logInfo(reporter.message("*KEY*", "Wrote TTPE artifact ''{0}''.", (outputFile != null) ? outputFile.getAbsolutePath() : uriStandardOutput));
                i.setFile(outputFile);
            }
        } catch (Exception e) {
            reporter.logError(e);
        } finally {
            IOUtil.closeSafely(bos);
            i.clearData(); // enable GC on images's data
        }
        return !fail && (reporter.getResourceErrors() == 0);
    }

    private BufferedOutputStream getFrameOutputStream(URI uri, File[] retOutputFile) throws IOException {
        String resourceName = getResourceNameComponent(uri);
        File d = new File(outputDirectory, resourceName);
        if (!d.exists())
            d.mkdir();
        if (d.exists()) {
            String outputFileName = MessageFormat.format(outputPattern, ++outputFileSequence);
            File outputFile = new File(d, outputFileName).getCanonicalFile();
            if (retOutputFile != null)
                retOutputFile[0] = outputFile;
            return new BufferedOutputStream(new FileOutputStream(outputFile));
            
        } else
            return null;
    }

    private String getResourceNameComponent(URI uri) {
        if (isFile(uri)) {
            String path = uri.getPath();
            int s = 0;
            int e = path.length();
            int lastPathSeparator = path.lastIndexOf('/');
            if (lastPathSeparator >= 0)
                s = lastPathSeparator + 1;
            int lastExtensionSeparator = path.lastIndexOf('.');
            if (lastExtensionSeparator >= 0)
                e = lastExtensionSeparator;
            return path.substring(s, e);
        } else
            return "stdin";
    }

    private boolean isFile(URI uri) {
        String scheme = uri.getScheme();
        if ((scheme == null) || !scheme.equals(uriFileScheme))
            return false;
        else
            return true;
    }

    private void archiveFrames(URI uri, List<Frame> frames, File archiveFile) {
        Reporter reporter = getReporter();
        BufferedOutputStream bos = null;
        ZipOutputStream zos = null;
        try {
            File[] retArchiveFile = new File[1];
            if ((bos = getArchiveOutputStream(uri, archiveFile, retArchiveFile)) != null) {
                archiveFile = retArchiveFile[0];
                zos = new ZipOutputStream(bos);
                Date now = new Date();
                writeManifestEntry(zos, now, frames);
                for (Frame f : frames) {
                    if (f.hasImages()) {
                        for (FrameImage i : f.getImages())
                            archiveFrame(i.getFile(), now, zos);
                    } else {
                        archiveFrame(f.getFile(), now, zos);
                    }
                }
            }
            reporter.logInfo(reporter.message("*KEY*", "Wrote TTPE archive ''{0}''.", (archiveFile != null) ? archiveFile.getAbsolutePath() : uriStandardOutput));
        } catch (IOException e) {
        } finally {
            IOUtil.closeSafely(zos);
            IOUtil.closeSafely(bos);
        }
    }

    private void archiveFrame(File f, Date now, ZipOutputStream zos) throws IOException {
        if (f != null) {
            ZipEntry ze = new ZipEntry(f.getName());
            ze.setTime(now.getTime());
            zos.putNextEntry(ze);
            writeFrameEntry(zos, f);
            zos.closeEntry();
        }
    }

    private void writeManifestEntry(ZipOutputStream zos, Date now, List<Frame> frames) {
        try {
            Manifest manifest = new Manifest();
            ZipEntry ze = new ZipEntry(manifest.getName());
            ze.setTime(now.getTime());
            zos.putNextEntry(ze);
            manifest.write(zos, frames, renderer.getName(), outputEncoding, outputIndent, this);
            zos.closeEntry();
        } catch (IOException e) {
        }
    }

    private void writeFrameEntry(ZipOutputStream zos, File f) {
        BufferedInputStream bis = null;
        try {
            bis = new BufferedInputStream(new FileInputStream(f));
            byte[] buffer = new byte[4096];
            int nb;
            while ((nb = bis.read(buffer, 0, buffer.length)) >= 0) {
                if (nb > 0) {
                    zos.write(buffer, 0, nb);
                } else
                    Thread.sleep(0);
            }
        } catch (InterruptedException e) {
        } catch (IOException e) {
        } finally {
            IOUtil.closeSafely(bis);
        }
    }

    private BufferedOutputStream getArchiveOutputStream(URI uri, File archiveFile, File[] retArchiveFile) throws IOException {
        if (archiveFile == null) {
            String resourceName = getResourceNameComponent(uri);
            File d = outputDirectory;
            if (d.exists()) {
                archiveFile = new File(d, resourceName + ".zip").getCanonicalFile();
            }
        }
        if (archiveFile != null) {
            if (retArchiveFile != null)
                retArchiveFile[0] = archiveFile;
            return new BufferedOutputStream(new FileOutputStream(archiveFile));
        } else
            return null;
    }

    private void removeFrameFiles(List<Frame> frames) {
        try {
            Map<String,File> directories = new java.util.HashMap<String,File>();
            for (Frame f : frames) {
                if (f instanceof DocumentFrame) {
                    removeFrameFile(f.getFile(), directories);
                } else if (f instanceof ImageFrame) {
                    if (f.hasImages()) {
                        for (FrameImage fi : f.getImages())
                            removeFrameFile(fi.getFile(), directories);
                    }
                }
            }
            removeFrameDirectories(directories);
        } catch (IOException e) {
            getReporter().logError(e);
        }
    }

    private void removeFrameFile(File f, Map<String,File> directories) throws IOException {
        Reporter reporter = getReporter();
        if (f != null) {
            if (f.delete()) {
                reporter.logInfo(reporter.message("*KEY*", "Removed TTPE artifact ''{0}''.", f.getAbsolutePath()));
                File d = f.getParentFile();
                directories.put(d.getCanonicalPath(), d);
            }
        }
    }

    private void removeFrameDirectories(Map<String,File> directories) {
        Reporter reporter = getReporter();
        for (File d : directories.values()) {
            if (d.list().length == 0) {
                if (d.delete()) {
                    reporter.logInfo(reporter.message("*KEY*", "Removed TTPE artifact directory ''{0}''.", d.getAbsolutePath()));
                }
            }
        }
    }

    public static void main(String[] args) {
        Runtime.getRuntime().exit(new Presenter().run(args));
    }

}
