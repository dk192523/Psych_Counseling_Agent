package com.dk.dkaiagent.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Retrieves short, timestamped excerpts from a raw transcript selected by the
 * first-stage case-summary search.
 */
@Component
@Slf4j
public class TranscriptSearchService {

    private static final Pattern SLUG_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}-call-\\d{2}$");
    private static final Pattern HAN_SEQUENCE_PATTERN = Pattern.compile("[\\p{IsHan}]{2,}");
    private static final Pattern ASCII_TERM_PATTERN = Pattern.compile("[a-z0-9]{2,}");
    private static final DateTimeFormatter CUE_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int CACHE_SIZE = 128;
    private static final int MAX_TERMS = 512;
    private static final int MAX_SNIPPETS = 3;
    private static final int MAX_SNIPPET_CHARS = 420;
    private static final int WINDOW_BEFORE = 4;
    private static final int WINDOW_AFTER = 7;

    private static final Set<String> STOP_TERMS = Set.of(
            "一个", "这个", "那个", "就是", "然后", "自己", "我们", "你们", "他们", "什么", "怎么",
            "问题", "觉得", "因为", "所以", "可以", "需要", "还是", "现在", "没有", "不是", "可能",
            "事情", "时候", "已经", "如果", "但是", "而且", "以及", "相关", "案例", "用户", "进行"
    );

    private final ObjectMapper objectMapper;
    private final Path transcriptDirectory;
    private final Map<String, RawTranscript> transcriptCache = Collections.synchronizedMap(
            new LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, RawTranscript> eldest) {
                    return size() > CACHE_SIZE;
                }
            }
    );

    @Autowired
    public TranscriptSearchService(
            ObjectMapper objectMapper,
            @Value("${app.rag.transcript-directory:../counseling-kb/raw}") String transcriptDirectory) {
        this(objectMapper, Path.of(transcriptDirectory));
    }

    TranscriptSearchService(ObjectMapper objectMapper, Path transcriptDirectory) {
        this.objectMapper = objectMapper;
        this.transcriptDirectory = transcriptDirectory.toAbsolutePath().normalize();
    }

    public Optional<TranscriptSource> search(String slug, String query, int requestedSnippetCount) {
        validateSlug(slug);
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }

        Optional<RawTranscript> transcriptOptional = loadTranscript(slug);
        if (transcriptOptional.isEmpty()) {
            return Optional.empty();
        }

        RawTranscript transcript = transcriptOptional.get();
        List<WeightedTerm> terms = extractTerms(query);
        int snippetCount = Math.max(1, Math.min(requestedSnippetCount, MAX_SNIPPETS));
        List<TranscriptSnippet> snippets = findSnippets(transcript, terms, snippetCount);
        return Optional.of(new TranscriptSource(
                transcript.slug(),
                transcript.title(),
                transcript.url(),
                snippets
        ));
    }

    public Path getTranscriptDirectory() {
        return transcriptDirectory;
    }

    private Optional<RawTranscript> loadTranscript(String slug) {
        RawTranscript cached = transcriptCache.get(slug);
        if (cached != null) {
            return Optional.of(cached);
        }

        Path transcriptPath = transcriptDirectory.resolve(slug + ".json").normalize();
        if (!transcriptPath.startsWith(transcriptDirectory)) {
            throw new IllegalArgumentException("invalid transcript slug");
        }
        if (!Files.isRegularFile(transcriptPath)) {
            log.debug("Raw transcript not found for slug {} under {}", slug, transcriptDirectory);
            return Optional.empty();
        }

        try {
            RawTranscript transcript = objectMapper.readValue(transcriptPath.toFile(), RawTranscript.class);
            if (!slug.equals(transcript.slug())) {
                log.warn("Transcript slug mismatch: requested {}, file contains {}", slug, transcript.slug());
                return Optional.empty();
            }
            transcriptCache.put(slug, transcript);
            return Optional.of(transcript);
        } catch (IOException e) {
            log.warn("Failed to read raw transcript {}", transcriptPath, e);
            return Optional.empty();
        }
    }

    private List<TranscriptSnippet> findSnippets(
            RawTranscript transcript,
            List<WeightedTerm> terms,
            int snippetCount) {
        List<RawCue> cues = transcript.cues() == null ? List.of() : transcript.cues();
        if (cues.isEmpty() || terms.isEmpty()) {
            return List.of();
        }

        List<Candidate> candidates = new ArrayList<>();
        for (int index = 0; index < cues.size(); index++) {
            String text = normalizeForMatching(cues.get(index).text());
            int cueScore = score(text, terms);
            if (cueScore <= 0) {
                continue;
            }
            int startIndex = Math.max(0, index - WINDOW_BEFORE);
            int endIndex = Math.min(cues.size() - 1, index + WINDOW_AFTER);
            String windowText = joinCueText(cues, startIndex, endIndex);
            int windowScore = score(normalizeForMatching(windowText), terms) + cueScore;
            candidates.add(new Candidate(startIndex, endIndex, windowScore, windowText));
        }

        candidates.sort(Comparator.comparingInt(Candidate::score).reversed()
                .thenComparingInt(Candidate::startIndex));

        List<Candidate> selected = new ArrayList<>();
        for (Candidate candidate : candidates) {
            boolean overlaps = selected.stream().anyMatch(existing ->
                    candidate.startIndex() <= existing.endIndex() + 2
                            && candidate.endIndex() >= existing.startIndex() - 2);
            if (overlaps) {
                continue;
            }
            selected.add(candidate);
            if (selected.size() == snippetCount) {
                break;
            }
        }

        List<TranscriptSnippet> snippets = new ArrayList<>();
        for (Candidate candidate : selected) {
            RawCue startCue = cues.get(candidate.startIndex());
            RawCue endCue = cues.get(candidate.endIndex());
            String text = truncate(candidate.text(), MAX_SNIPPET_CHARS);
            snippets.add(new TranscriptSnippet(
                    startCue.start(),
                    endCue.end(),
                    text,
                    buildTimestampUrl(transcript.url(), startCue.start()),
                    candidate.score()
            ));
        }
        return List.copyOf(snippets);
    }

    private List<WeightedTerm> extractTerms(String query) {
        String normalizedQuery = Normalizer.normalize(query, Normalizer.Form.NFKC).toLowerCase();
        Map<String, Integer> terms = new LinkedHashMap<>();

        Matcher hanMatcher = HAN_SEQUENCE_PATTERN.matcher(normalizedQuery);
        while (hanMatcher.find() && terms.size() < MAX_TERMS) {
            String sequence = hanMatcher.group();
            if (sequence.length() <= 12 && !STOP_TERMS.contains(sequence)) {
                terms.merge(sequence, Math.min(sequence.length() * 2, 16), Math::max);
            }
            for (int size = 4; size >= 2 && terms.size() < MAX_TERMS; size--) {
                for (int start = 0; start + size <= sequence.length() && terms.size() < MAX_TERMS; start++) {
                    String term = sequence.substring(start, start + size);
                    if (!STOP_TERMS.contains(term)) {
                        terms.merge(term, size * size, Math::max);
                    }
                }
            }
        }

        Matcher asciiMatcher = ASCII_TERM_PATTERN.matcher(normalizedQuery);
        while (asciiMatcher.find() && terms.size() < MAX_TERMS) {
            String term = asciiMatcher.group();
            terms.merge(term, Math.min(term.length() * 2, 16), Math::max);
        }

        return terms.entrySet().stream()
                .map(entry -> new WeightedTerm(entry.getKey(), entry.getValue()))
                .toList();
    }

    private int score(String normalizedText, List<WeightedTerm> terms) {
        int score = 0;
        for (WeightedTerm term : terms) {
            if (normalizedText.contains(term.value())) {
                score += term.weight();
            }
        }
        return score;
    }

    private String joinCueText(List<RawCue> cues, int startIndex, int endIndex) {
        StringBuilder builder = new StringBuilder();
        for (int index = startIndex; index <= endIndex; index++) {
            String cueText = cues.get(index).text();
            if (cueText == null || cueText.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(cueText.trim());
        }
        return builder.toString();
    }

    private String normalizeForMatching(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase()
                .replaceAll("[^\\p{IsHan}a-z0-9]", "");
    }

    private String buildTimestampUrl(String videoUrl, String start) {
        if (videoUrl == null || videoUrl.isBlank()) {
            return "";
        }
        int seconds = parseSeconds(start);
        String separator = videoUrl.contains("?") ? "&" : "?";
        return videoUrl + separator + "t=" + seconds;
    }

    private int parseSeconds(String timestamp) {
        try {
            LocalTime time = LocalTime.parse(timestamp, CUE_TIME_FORMATTER);
            return time.toSecondOfDay();
        } catch (DateTimeParseException | NullPointerException e) {
            return 0;
        }
    }

    private String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars - 1) + "…";
    }

    private void validateSlug(String slug) {
        if (slug == null || !SLUG_PATTERN.matcher(slug).matches()) {
            throw new IllegalArgumentException("invalid transcript slug");
        }
    }

    public record TranscriptSource(
            String slug,
            String title,
            String videoUrl,
            List<TranscriptSnippet> snippets) {

        public String formatForContext() {
            StringBuilder builder = new StringBuilder()
                    .append("案例：").append(title).append('\n')
                    .append("案例编号：").append(slug).append('\n')
                    .append("视频：").append(videoUrl == null ? "" : videoUrl).append('\n');
            for (TranscriptSnippet snippet : snippets) {
                builder.append("[")
                        .append(slug).append(' ')
                        .append(snippet.start()).append('-').append(snippet.end())
                        .append("] ")
                        .append(snippet.text())
                        .append('\n')
                        .append("定位：").append(snippet.sourceUrl()).append('\n');
            }
            return builder.toString().trim();
        }
    }

    public record TranscriptSnippet(
            String start,
            String end,
            String text,
            String sourceUrl,
            int score) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawTranscript(String slug, String title, String url, List<RawCue> cues) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawCue(int number, String start, String end, String text) {
    }

    private record WeightedTerm(String value, int weight) {
    }

    private record Candidate(int startIndex, int endIndex, int score, String text) {
    }
}
