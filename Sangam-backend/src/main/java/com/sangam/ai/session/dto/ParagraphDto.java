package com.sangam.ai.session.dto;

import com.sangam.ai.session.Paragraph;
import java.util.UUID;

public record ParagraphDto(
        UUID id,
        int index,
        String content,
        // How many child nodes exist under this paragraph
        // Frontend uses this to show "3 questions" badge
        int childNodeCount
) {
    public static ParagraphDto display(UUID id, int index, String content, int childCount) {
        return new ParagraphDto(id, index, content, childCount);
    }

    public static ParagraphDto from(Paragraph p, int childCount) {
        return display(p.getId(), p.getIndex(), p.getContent(), childCount);
    }
}
