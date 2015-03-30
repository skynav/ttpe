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

package com.skynav.ttpe.layout;

import java.text.AttributedCharacterIterator;
import java.text.CharacterIterator;
import java.util.List;
import java.util.Set;

import com.skynav.ttpe.area.AnnotationArea;
import com.skynav.ttpe.area.AreaNode;
import com.skynav.ttpe.area.GlyphArea;
import com.skynav.ttpe.area.InlineBlockArea;
import com.skynav.ttpe.area.InlineFillerArea;
import com.skynav.ttpe.area.LineArea;
import com.skynav.ttpe.area.SpaceArea;
import com.skynav.ttpe.geometry.Direction;
import com.skynav.ttpe.geometry.WritingMode;
import com.skynav.ttpe.fonts.Font;
import com.skynav.ttpe.fonts.FontFeature;
import com.skynav.ttpe.style.AnnotationPosition;
import com.skynav.ttpe.style.Color;
import com.skynav.ttpe.style.InlineAlignment;
import com.skynav.ttpe.style.LineFeedTreatment;
import com.skynav.ttpe.style.StyleAttribute;
import com.skynav.ttpe.style.StyleAttributeInterval;
import com.skynav.ttpe.style.SuppressAtLineBreakTreatment;
import com.skynav.ttpe.style.Whitespace;
import com.skynav.ttpe.style.WhitespaceTreatment;
import com.skynav.ttpe.style.Wrap;
import com.skynav.ttpe.text.LineBreakIterator;
import com.skynav.ttpe.text.Paragraph;
import com.skynav.ttpe.text.Phrase;
import com.skynav.ttpe.util.Characters;
import com.skynav.ttpe.util.Integers;
import com.skynav.ttpe.util.Strings;

import static com.skynav.ttpe.geometry.Dimension.*;

/* Suppressing PMD warnings for the time being.
 * This should be reviewed after Beta-deliverable is met at the end of
 * April 2015 */
@SuppressWarnings("PMD")
public class LineLayout {

    private enum InlineBreak {
        HARD,
        SOFT_IDEOGRAPH,
        SOFT_HYPHENATION_POINT,
        SOFT_WHITESPACE,
        UNKNOWN;
        boolean isHard() {
            return this == HARD;
        }
        @SuppressWarnings("unused")
        boolean isSoft() {
            return !isHard();
        }
    }

    // content state
    private Phrase content;
    private AttributedCharacterIterator iterator;

    // layout state
    private LayoutState state;
    private int lineNumber;

    // style related state
    private Color color;
    private InlineAlignment textAlign;
    private Wrap wrap;
    private WritingMode writingMode;

    // derived style state
    private Font font;
    private double lineHeight;
    private WhitespaceState whitespace;

    public LineLayout(Phrase content, LayoutState state) {
        // content state
        this.content = content;
        this.iterator = content.getIterator();
        this.state = state;
        // area derived state
        this.writingMode = state.getWritingMode();
        // paragraph specified styles
        this.color = content.getColor(-1);
        this.textAlign = relativizeAlignment(content.getTextAlign(-1), this.writingMode);
        this.wrap = content.getWrapOption(-1);
        // derived styles
        this.font = content.getFont(-1);
        this.lineHeight = content.getLineHeight(-1, font);
        this.whitespace = new WhitespaceState(state.getWhitespace());
    }

    private static InlineAlignment relativizeAlignment(InlineAlignment alignment, WritingMode wm) {
        Direction direction = wm.getDirection(IPD);
        if (alignment == InlineAlignment.LEFT) {
            if (direction == Direction.LR)
                alignment = InlineAlignment.START;
            else if (direction == Direction.RL)
                alignment = InlineAlignment.END;
        } else if (alignment == InlineAlignment.RIGHT) {
            if (direction == Direction.RL)
                alignment = InlineAlignment.START;
            else if (direction == Direction.LR)
                alignment = InlineAlignment.END;
        }
        return alignment;
    }

    public List<? extends LineArea> layout(double available, Consume consume) {
        List<LineArea> lines = new java.util.ArrayList<LineArea>();
        if (available > 0) {
            double consumed = 0;
            List<InlineBreakOpportunity> breaks = new java.util.ArrayList<InlineBreakOpportunity>();
            LineBreakIterator lbi = state.getBreakIterator();
            LineBreakIterator lci = state.getCharacterIterator();
            LineBreakIterator bi;
            for (TextRun r = getNextTextRun(); r != null;) {
                if (!breaks.isEmpty() || !r.suppressAfterLineBreak()) {
                    bi = updateIterator(lbi, r);
                    for (InlineBreakOpportunity b = getNextBreakOpportunity(bi, r, available - consumed); b != null; ) {
                        if (b.isHard()) {
                            lines.add(emit(available, consumed, consume, breaks));
                            consumed = 0;
                            break;
                        } else {
                            double advance = b.advance;
                            if ((consumed + advance) > available) {
                                if (wrap == Wrap.WRAP) {
                                    if (!breaks.isEmpty()) {
                                        lines.add(emit(available, consumed, consume, breaks));
                                        consumed = 0;
                                    } else {
                                        if (bi != lci)
                                            bi = updateIterator(lci, b);
                                        b = getNextBreakOpportunity(bi, r, available - consumed);
                                    }
                                    continue;
                                }
                            }
                            breaks.add(b);
                            consumed += advance;
                            b = getNextBreakOpportunity(bi, r, available - consumed);
                        }
                    }
                }
                r = getNextTextRun();
            }
            if (!breaks.isEmpty())
                lines.add(emit(available, consumed, consume, breaks));
        }
        return align(lines);
    }

    private static final StyleAttribute[] embeddingAttr = new StyleAttribute[] { StyleAttribute.EMBEDDING };
    private TextRun getNextTextRun() {
        int s = iterator.getIndex();
        char c = iterator.current();
        if (c == CharacterIterator.DONE)
            return null;
        else if (c == Characters.UC_OBJECT) {
            Object embedding = iterator.getAttribute(embeddingAttr[0]);
            iterator.setIndex(s + 1);
            return new EmbeddingRun(s, embedding);
        }
        boolean inBreakingWhitespace = Characters.isBreakingWhitespace(c);
        while ((c = iterator.next()) != CharacterIterator.DONE) {
            if (c == Characters.UC_OBJECT)
                break;
            else if (inBreakingWhitespace ^ Characters.isBreakingWhitespace(c))
                break;
            else if (hasBreakingAttribute(iterator))
                break;
        }
        int e = iterator.getIndex();
        return inBreakingWhitespace ? new WhitespaceRun(s, e, whitespace) : new NonWhitespaceRun(s, e);
    }

    private static boolean hasBreakingAttribute(AttributedCharacterIterator iterator) {
        return hasBreakingAttribute(iterator, iterator.getIndex());
    }

    private static Set<StyleAttribute> breakingAttributes;
    static {
        breakingAttributes = new java.util.HashSet<StyleAttribute>();
        breakingAttributes.add(StyleAttribute.ANNOTATIONS);
    }

    private static boolean hasBreakingAttribute(AttributedCharacterIterator iterator, int index) {
        int s = iterator.getIndex();
        if (index != s)
            iterator.setIndex(index);
        int k = iterator.getRunStart(breakingAttributes);
        if (index != s)
            iterator.setIndex(s);
        return k == index;
    }

    private LineBreakIterator updateIterator(LineBreakIterator bi, TextRun r) {
        bi.setText(r.getText());
        bi.first();
        return bi;
    }

    private LineBreakIterator updateIterator(LineBreakIterator bi, InlineBreakOpportunity b) {
        bi.setText(b.run.getText(b.start));
        bi.first();
        return bi;
    }

    private InlineBreakOpportunity getNextBreakOpportunity(LineBreakIterator bi, TextRun r, double available) {
        if (bi != null) {
            int from = bi.current();
            int to = bi.next();
            if (to != LineBreakIterator.DONE)
                return new InlineBreakOpportunity(r, r.getInlineBreak(to), from, to, r.getAdvance(from, to, available));
        }
        return null;
    }

    private LineArea emit(double available, double consumed, Consume consume, List<InlineBreakOpportunity> breaks) {
        consumed = maybeRemoveLeading(breaks, consumed);
        consumed = maybeRemoveTrailing(breaks, consumed);
        return addTextAreas(newLine(content, consume == Consume.MAX ? available : consumed, lineHeight, textAlign, color, font), breaks);
    }

    protected LineArea newLine(Phrase p, double ipd, double bpd, InlineAlignment textAlign, Color color, Font font) {
        return new LineArea(p.getElement(), ipd, bpd, textAlign, color, font, ++lineNumber);
    }

    protected int getNextLineNumber() {
        return ++lineNumber;
    }

    private LineArea addTextAreas(LineArea l, List<InlineBreakOpportunity> breaks) {
        if (!breaks.isEmpty()) {
            int savedIndex = iterator.getIndex();
            StringBuffer sb = new StringBuffer();
            TextRun lastRun = null;
            int lastRunStart = -1;
            double advance = 0;
            for (InlineBreakOpportunity b : breaks) {
                TextRun r = b.run;
                if ((lastRun != null) && (r != lastRun)) {
                    maybeAddAnnotationAreas(l, lastRunStart, font, advance, lineHeight);
                    addTextArea(l, sb.toString(), font, advance, lineHeight, lastRun);
                    sb.setLength(0);
                    advance = 0;
                }
                sb.append(r.getText(b.start, b.index));
                advance += b.advance;
                lastRun = r;
                lastRunStart = r.start + b.start;
            }
            if (sb.length() > 0) {
                maybeAddAnnotationAreas(l, lastRunStart, font, advance, lineHeight);
                addTextArea(l, sb.toString(), font, advance, lineHeight, lastRun);
            }
            iterator.setIndex(savedIndex);
            breaks.clear();
        }
        return l;
    }

    private double maybeRemoveLeading(List<InlineBreakOpportunity> breaks, double consumed) {
        while (!breaks.isEmpty()) {
            int i = 0;
            InlineBreakOpportunity b = breaks.get(i);
            if (b.suppressAfterLineBreak()) {
                breaks.remove(i);
                consumed -= b.advance;
            } else
                break;
        }
        return consumed;
    }

    private double maybeRemoveTrailing(List<InlineBreakOpportunity> breaks, double consumed) {
        while (!breaks.isEmpty()) {
            int i = breaks.size() - 1;
            InlineBreakOpportunity b = breaks.get(i);
            if (b.suppressBeforeLineBreak()) {
                breaks.remove(i);
                consumed -= b.advance;
            } else
                break;
        }
        return consumed;
    }

    private void addTextArea(LineArea l, String text, Font font, double advance, double lineHeight, TextRun run) {
        if (run instanceof WhitespaceRun)
            l.addChild(new SpaceArea(content.getElement(), advance, lineHeight, text, font), LineArea.ENCLOSE_ALL);
        else if (run instanceof EmbeddingRun)
            l.addChild(((EmbeddingRun) run).getArea(), LineArea.ENCLOSE_ALL); 
        else if (run instanceof NonWhitespaceRun) {
            int start = run.start;
            for (StyleAttributeInterval fai : run.getFontIntervals()) {
                if (fai.isOuterScope()) {
                    l.addChild(new GlyphArea(content.getElement(), advance, lineHeight, text, font), LineArea.ENCLOSE_ALL);
                    break;
                } else {
                    int f = fai.getBegin() - start;
                    assert f >= 0;
                    assert f < text.length();
                    int t = fai.getEnd() - start;
                    assert t >= 0;
                    assert t >= f;
                    assert t <= text.length();
                    Font fontSegment = (Font) fai.getValue();
                    String segment = text.substring(f, t);
                    double di = fontSegment.getAdvance(segment);
                    l.addChild(new GlyphArea(content.getElement(), di, lineHeight, segment, fontSegment), LineArea.ENCLOSE_ALL);
                }
            }
        }
    }

    private void maybeAddAnnotationAreas(LineArea l, int start, Font font, double advance, double lineHeight) {
        if (start >= 0) {
            iterator.setIndex(start);
            Phrase[] annotations = (Phrase[]) iterator.getAttribute(StyleAttribute.ANNOTATIONS);
            if (annotations != null)
                addAnnotationAreas(l, annotations, font, advance, lineHeight);
        }
    }

    private void addAnnotationAreas(LineArea l, Phrase[] annotations, Font font, double advance, double lineHeight) {
        for (Phrase p : annotations) {
            InlineAlignment annotationAlign = p.getAnnotationAlign(-1);
            Double annotationOffset = p.getAnnotationOffset(-1);
            AnnotationPosition annotationPosition = p.getAnnotationPosition(-1);
            for (AnnotationArea a :  new AnnotationLayout(p, state).layout()) {
                a.setAlignment(annotationAlign);
                a.setOffset(annotationOffset);
                a.setPosition(annotationPosition);
                alignTextAreas(a, advance, annotationAlign);
                l.addChild(a, LineArea.ENCLOSE_ALL);
            }
        }
    }

    private List<LineArea> align(List<LineArea> lines) {
        double maxMeasure = 0;
        for (LineArea l : lines) {
            double measure = l.getIPD();
            if (measure > maxMeasure)
                maxMeasure = measure;
        }
        for (LineArea l : lines) {
            alignTextAreas(l, maxMeasure, textAlign);
            l.setIPD(maxMeasure);
        }
        return lines;
    }

    private void alignTextAreas(LineArea l, double measure, InlineAlignment alignment) {
        double consumed = 0;
        for (AreaNode c : l.getChildren()) {
            if (c instanceof AnnotationArea)
                continue;
            else
                consumed += c.getIPD();
        }
        double available = measure - consumed;
        if (available > 0) {
            if (alignment == InlineAlignment.START) {
                AreaNode a = new InlineFillerArea(l.getElement(), available, 0);
                l.addChild(a, LineArea.EXPAND_IPD);
            } else if (alignment == InlineAlignment.END) {
                AreaNode a = new InlineFillerArea(l.getElement(), available, 0);
                l.insertChild(a, l.firstChild(), LineArea.EXPAND_IPD);
            } else if (alignment == InlineAlignment.CENTER) {
                double half = available / 2;
                AreaNode a1 = new InlineFillerArea(l.getElement(), half, 0);
                AreaNode a2 = new InlineFillerArea(l.getElement(), half, 0);
                l.insertChild(a1, l.firstChild(), LineArea.EXPAND_IPD);
                l.insertChild(a2, null, LineArea.EXPAND_IPD);
            } else {
                l = justifyTextAreas(l, measure, alignment);
            }
        } else if (available < 0) {
            l.setOverflow(-available);
        }
    }

    private LineArea justifyTextAreas(LineArea l, double measure, InlineAlignment alignment) {
        return l;
    }

    private static class WhitespaceState {
        LineFeedTreatment lineFeedTreatment;
        SuppressAtLineBreakTreatment suppressAtLineBreakTreatment;
        boolean whitespaceCollapse;
        @SuppressWarnings("unused")
        WhitespaceTreatment whitespaceTreatment;
        WhitespaceState(Whitespace whitespace) {
            LineFeedTreatment lineFeedTreatment;
            SuppressAtLineBreakTreatment suppressAtLineBreakTreatment;
            boolean whitespaceCollapse;
            WhitespaceTreatment whitespaceTreatment;
            if (whitespace == Whitespace.DEFAULT) {
                lineFeedTreatment = LineFeedTreatment.TREAT_AS_SPACE;
                suppressAtLineBreakTreatment = SuppressAtLineBreakTreatment.AUTO;
                whitespaceCollapse = true;
                whitespaceTreatment = WhitespaceTreatment.IGNORE_IF_SURROUNDING_LINEFEED;
            } else if (whitespace == Whitespace.PRESERVE) {
                lineFeedTreatment = LineFeedTreatment.PRESERVE;
                suppressAtLineBreakTreatment = SuppressAtLineBreakTreatment.RETAIN;
                whitespaceCollapse = false;
                whitespaceTreatment = WhitespaceTreatment.PRESERVE;
            } else
                throw new IllegalArgumentException();
            this.lineFeedTreatment = lineFeedTreatment;
            this.suppressAtLineBreakTreatment = suppressAtLineBreakTreatment;
            this.whitespaceCollapse = whitespaceCollapse;
            this.whitespaceTreatment = whitespaceTreatment;
        }
    }

    private static class InlineBreakOpportunity {
        TextRun run;            // associated text run
        InlineBreak type;       // type of break
        int start;              // start index within text run
        int index;              // index (of break) within text run
        double advance;         // advance (in IPD) within text run
        InlineBreakOpportunity(TextRun run, InlineBreak type, int start, int index, double advance) {
            this.run = run;
            this.type = type;
            this.start = start;
            this.index = index;
            this.advance = advance;
        }
        @Override
        public String toString() {
            StringBuffer sb = new StringBuffer();
            sb.append(run);
            sb.append('[');
            sb.append(start);
            sb.append(',');
            sb.append(index);
            sb.append(',');
            sb.append(Double.toString(advance));
            sb.append(']');
            return sb.toString();
        }
        boolean isHard() {
            if (type.isHard())
                return true;
            else if (run instanceof NonWhitespaceRun)
                return false;
            else {
                String lwsp = run.getText(start, index);
                return (lwsp.length() == 1) && (lwsp.charAt(0) == Characters.UC_LINE_SEPARATOR);
            }
        }
        boolean suppressAfterLineBreak() {
            return run.suppressAfterLineBreak();
        }
        boolean suppressBeforeLineBreak() {
            return run.suppressBeforeLineBreak();
        }
    }

    private class TextRun {
        int start;                                              // start index in outer iterator
        int end;                                                // end index in outer iterator
        List<StyleAttributeInterval> fontIntervals;             // cached font sub-intervals over complete run interval
        String text;                                            // cached text over complete run interval
        TextRun(int start, int end) {
            this.start = start;
            this.end = end;
            this.fontIntervals = getFontIntervals(0, end - start, font);
        }
        @Override
        public String toString() {
            StringBuffer sb = new StringBuffer();
            sb.append(getText());
            return sb.toString();
        }
        // obtain all font intervals associated with run
        List<StyleAttributeInterval> getFontIntervals() {
            return fontIntervals;
        }
        // obtain all of text associated with run
        String getText() {
            if (text == null)
                text = getText(0);
            return text;
        }
        // obtain text starting at FROM to END of run, where FROM is index into run, not outer iterator
        String getText(int from) {
            return getText(from, from + (end - start));
        }
        // obtain text starting at FROM to TO of run, where FROM and TO are indices into run, not outer iterator
        String getText(int from, int to) {
            StringBuffer sb = new StringBuffer();
            int savedIndex = iterator.getIndex();
            for (int i = start + from, e = start + to; i < e; ++i) {
                sb.append(iterator.setIndex(i));
            }
            iterator.setIndex(savedIndex);
            return sb.toString();
        }
        // obtain inline break type at INDEX of run, where INDEX is index into run, not outer iterator
        InlineBreak getInlineBreak(int index) {
            return InlineBreak.UNKNOWN;
        }
        // obtain advance of text starting at FROM to TO of run, where FROM and TO are indices into run, not outer iterator
        double getAdvance(int from, int to, double available) {
            double advance = 0;
            for (StyleAttributeInterval fai : fontIntervals) {
                if (fai.isOuterScope()) {
                    advance += ((Font) fai.getValue()).getAdvance(getText().substring(from, to));
                    break;
                } else {
                    int[] intersection = fai.intersection(start + from, start + to);
                    if (intersection != null) {
                        int f = intersection[0] - start;
                        int t = intersection[1] - start;
                        advance += ((Font) fai.getValue()).getAdvance(getText().substring(f,t));
                    }
                }
            }
            return advance;
        }
        // determine if content associate with break is suppressed after line break
        boolean suppressAfterLineBreak() {
            return false;
        }
        // determine if content associate with break is suppressed before line break
        boolean suppressBeforeLineBreak() {
            return false;
        }
        // obtain fonts for specified interval FROM to TO of run
        private List<StyleAttributeInterval> getFontIntervals(int from, int to, Font defaultFont) {
            StyleAttribute fontAttr = StyleAttribute.FONT;
            List<StyleAttributeInterval> fonts = new java.util.ArrayList<StyleAttributeInterval>();
            int[] intervals = getAttributeIntervals(from, to, StyleAttribute.FONT);
            AttributedCharacterIterator aci = iterator;
            int savedIndex = aci.getIndex();
            for (int i = 0, n = intervals.length / 2; i < n; ++i) {
                int s = start + intervals[i*2 + 0];
                int e = start + intervals[i*2 + 1];
                iterator.setIndex(s);
                Object v = aci.getAttribute(fontAttr);
                if (v == null)
                    v = defaultFont;
                fonts.add(new StyleAttributeInterval(fontAttr, v, s, e));
            }
            aci.setIndex(savedIndex);
            if (fonts.isEmpty())
                fonts.add(new StyleAttributeInterval(fontAttr, defaultFont, -1, -1));
            return fonts;
        }
        // obtain intervals over [FROM,TO) for which ATTRIBUTE is defined
        private int[] getAttributeIntervals(int from, int to, StyleAttribute attribute) {
            List<Integer> indices = new java.util.ArrayList<Integer>();
            AttributedCharacterIterator aci = iterator;
            int savedIndex = aci.getIndex();
            int b = start;
            int e = end;
            aci.setIndex(b);
            while (aci.getIndex() < e) {
                int s = aci.getRunStart(attribute);
                int l = aci.getRunLimit(attribute);
                if (s < b)
                    s = b;
                indices.add(s - b);
                aci.setIndex(l);
                if (l > e)
                    l = e;
                indices.add(l - b);
            }
            aci.setIndex(savedIndex);
            return Integers.toArray(indices);
        }
    }

    private class WhitespaceRun extends TextRun {
        private WhitespaceState whitespace;
        WhitespaceRun(int start, int end, WhitespaceState whitespace) {
            super(start, end);
            this.whitespace = whitespace;
        }
        @Override
        String getText(int from, int to) {
            String t = super.getText(from, to);
            t = processLineFeedTreatment(t, whitespace.lineFeedTreatment);
            t = processWhitespaceCollapse(t, whitespace.whitespaceCollapse);
            return t;
        }
        @Override
        boolean suppressAfterLineBreak() {
            return suppressAtLineBreak(whitespace.suppressAtLineBreakTreatment, false);
        }
        @Override
        boolean suppressBeforeLineBreak() {
            return suppressAtLineBreak(whitespace.suppressAtLineBreakTreatment, true);
        }
        private String processLineFeedTreatment(String t, LineFeedTreatment treatment) {
            if (treatment == LineFeedTreatment.PRESERVE)
                return t;
            else {
                StringBuffer sb = new StringBuffer();
                for (int i = 0, n = t.length(); i < n; ++i) {
                    char c = t.charAt(i);
                    if (c != Characters.UC_LF)
                        sb.append(c);
                    else if (treatment == LineFeedTreatment.IGNORE)
                        continue;
                    else if (treatment == LineFeedTreatment.TREAT_AS_SPACE)
                        sb.append((char) Characters.UC_SPACE);
                    else if (treatment == LineFeedTreatment.TREAT_AS_ZERO_WIDTH_SPACE)
                        sb.append((char) Characters.UC_SPACE_ZWSP);
                }
                return sb.toString();
            }
        }
        private String processWhitespaceCollapse(String t, boolean collapse) {
            if (!collapse)
                return t;
            else {
                StringBuffer sb = new StringBuffer();
                boolean inSpace = false;
                for (int i = 0, n = t.length(); i < n; ++i) {
                    char c = t.charAt(i);
                    if (c == Characters.UC_SPACE)
                        inSpace = true;
                    else {
                        if (inSpace)
                            sb.append((char) Characters.UC_SPACE);
                        sb.append(c);
                        inSpace = false;
                    }
                }
                if (inSpace)
                    sb.append((char) Characters.UC_SPACE);
                return sb.toString();
            }
        }
        private boolean suppressAtLineBreak(SuppressAtLineBreakTreatment suppressAtLineBreakTreatment, boolean before) {
            if (suppressAtLineBreakTreatment == SuppressAtLineBreakTreatment.RETAIN)
                return false;
            else if (suppressAtLineBreakTreatment == SuppressAtLineBreakTreatment.SUPPRESS)
                return true;
            else if (suppressAtLineBreakTreatment == SuppressAtLineBreakTreatment.AUTO)
                return suppressAtLineBreakAuto(before);
            else
                return false;
        }
        private boolean suppressAtLineBreakAuto(boolean before) {
            String t = getText();
            for (int i = 0, n = t.length(); i < n; ++i) {
                char c = t.charAt(i);
                if (c != Characters.UC_SPACE)
                    return false;
            }
            return true;
        }
    }

    private class NonWhitespaceRun extends TextRun {
        NonWhitespaceRun(int start, int end) {
            super(start, end);
        }
        @Override
        String getText(int from, int to) {
            String text = super.getText(from, to);
            StringBuffer sb = new StringBuffer();
            for (StyleAttributeInterval fai : fontIntervals) {
                if (fai.isOuterScope()) {
                    sb.append(processFeatures(text, (Font) fai.getValue()));
                    break;
                } else {
                    int[] intersection = fai.intersection(start + from, start + to);
                    if (intersection != null) {
                        int f = intersection[0] - start - from;
                        int t = intersection[1] - start - from;
                        sb.append(processFeatures(text.substring(f,t), (Font) fai.getValue()));
                    }
                }
            }
            return sb.toString();
        }
        private String processFeatures(String t, Font font) {
            for (FontFeature feature : font.getFeatures()) {
                if (feature.getFeature().equals("hwid"))
                    t = processHalfWidth(t);
                else if (feature.getFeature().equals("fwid"))
                    t = processFullWidth(t);
            }
            return t;
        }
        private String processHalfWidth(String t) {
            return Strings.toHalfWidth(t);
        }
        private String processFullWidth(String t) {
            return Strings.toFullWidth(t);
        }
    }

    private class EmbeddingRun extends NonWhitespaceRun {
        private Object embedding;
        private InlineBlockArea area;
        EmbeddingRun(int index, Object embedding) {
            super(index, index + 1);
            this.embedding = embedding;
        }
        double getAdvance(int from, int to, double available) {
            // format embedding using available width
            if (area == null)
                area = layoutEmbedding(available);
            if (area != null)
                return area.getIPD();
            else
                return 0;
        }
        InlineBlockArea getArea() {
            return area;
        }
        private InlineBlockArea layoutEmbedding(double available) {
            if (embedding instanceof Paragraph)
                return layoutEmbedding((Paragraph) embedding, available);
            else
                return null;
        }
        private InlineBlockArea layoutEmbedding(Paragraph embedding, double available) {
            InlineBlockArea area = new InlineBlockArea(embedding.getElement());
            area.addChildren(new ParagraphLayout(embedding, state).layout(available, Consume.FIT), LineArea.ENCLOSE_ALL);
            return area;
        }
    }

}

