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

package com.skynav.ttpe.area;

import java.util.Set;

import org.w3c.dom.Element;

import com.skynav.ttpe.fonts.Font;
import com.skynav.ttpe.style.AnnotationPosition;
import com.skynav.ttpe.style.Color;
import com.skynav.ttpe.style.InlineAlignment;

/* Suppressing PMD warnings for the time being.
 * This should be reviewed after Beta-deliverable is met at the end of
 * April 2015 */
@SuppressWarnings("PMD")
public class LineArea extends BlockArea {

    private InlineAlignment alignment;
    private Color color;
    private Font font;
    private int lineNumber;                     // 1 is first line of containing area
    private double overflow;
    private double bpdAnnotationBefore;
    private double bpdAnnotationAfter;

    public LineArea(Element e, double ipd, double bpd, InlineAlignment alignment, Color color, Font font, int lineNumber) {
        super(e, ipd, bpd);
        this.alignment = alignment;
        this.color = color;
        this.font = font;
        this.lineNumber = lineNumber;
    }

    @Override
    public void addChild(AreaNode c, Set<Expansion> expansions) {
        if (c instanceof Inline) {
            super.addChild(c, expansions);
            maybeUpdateForAnnotation(c);
        } else
            throw new IllegalArgumentException();
    }

    @Override
    public void insertChild(AreaNode c, AreaNode cBefore, Set<Expansion> expansions) {
        if (c instanceof Inline) {
            super.insertChild(c, cBefore, expansions);
            maybeUpdateForAnnotation(c);
        } else
            throw new IllegalArgumentException();
    }

    @Override
    public void expand(AreaNode a, Set<Expansion> expansions) {
        if (a instanceof AnnotationArea)
            return;
        else
            super.expand(a, expansions);
    }

    public void setAlignment(InlineAlignment alignment) {
        this.alignment = alignment;
    }

    public InlineAlignment getAlignment() {
        return alignment;
    }

    public Color getColor() {
        return color;
    }

    public Font getFont() {
        return font;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public boolean isFirstLine() {
        return lineNumber == 1;
    }

    public double getLeadingBefore() {
        return font.getLeading() / 2;
    }

    public double getLeadingAfter() {
        return font.getLeading() / 2;
    }

    public double getAscent() {
        return font.getAscent();
    }

    public double getDescent() {
        return font.getDescent();
    }

    public void setOverflow(double overflow) {
        this.overflow = overflow;
    }

    public double getOverflow() {
        return overflow;
    }

    public double getAnnotationBPD(AnnotationPosition position) {
        if (position == AnnotationPosition.BEFORE)
            return bpdAnnotationBefore;
        else if (position == AnnotationPosition.AFTER)
            return bpdAnnotationAfter;
        else
            return 0;
    }

    private void maybeUpdateForAnnotation(AreaNode c) {
        if (c instanceof AnnotationArea) {
            AnnotationArea a = (AnnotationArea) c;
            AnnotationPosition position = a.getPosition();
            if (position == AnnotationPosition.AUTO) {
                position = isFirstLine() ? AnnotationPosition.BEFORE : AnnotationPosition.AFTER;
                a.setPosition(position);
            }
            double bpdAnnotation = a.getBPD();
            if (position == AnnotationPosition.BEFORE) {
                if (bpdAnnotationBefore < bpdAnnotation)
                    bpdAnnotationBefore = bpdAnnotation;
            } else if (position == AnnotationPosition.AFTER) {
                if (bpdAnnotationAfter < bpdAnnotation)
                    bpdAnnotationAfter = bpdAnnotation;
            }
        }
    }

}