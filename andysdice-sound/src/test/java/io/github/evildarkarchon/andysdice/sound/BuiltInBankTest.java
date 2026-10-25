package io.github.evildarkarchon.andysdice.sound;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import org.junit.jupiter.api.Test;

/**
 * The built-in sample bank (ADR-0012 "Samples", spec §3.5): at least 8 mono 16-bit 48 kHz WAV slices per
 * category, every one listed in {@code SOURCES.md} with its source URL, author, licence and retrieval date, and
 * every entry there a slice that exists.
 */
class BuiltInBankTest {

    /** A table row of {@code SOURCES.md}: file, source URL, author, licence, retrieval date, what it was cut from. */
    private static final Pattern ROW = Pattern.compile(
            "^\\| `([a-z-]+-\\d{2}\\.wav)` \\| (\\S+) \\| ([^|]+?) \\| ([^|]+?) \\| (\\d{4}-\\d{2}-\\d{2}) \\| (.+) \\|$");

    /** A Freesound per-file URL, as each pack's readme gives it, or Kenney's Casino Audio page. */
    private static final Pattern SOURCE = Pattern.compile(
            "https://freesound\\.org/s/(\\d+)/|https://kenney\\.nl/assets/casino-audio");

    private record Entry(String file, String url, String author, String licence, LocalDate retrieved,
            String cutFrom) {
    }

    private static Path folder() throws URISyntaxException {
        return Path.of(SampleBank.class.getResource("sample-bank/SOURCES.md").toURI()).getParent();
    }

    private static List<String> slicesOnDisk() throws Exception {
        try (Stream<Path> files = Files.list(folder())) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".wav")).sorted().toList();
        }
    }

    private static List<Entry> entries() throws Exception {
        List<Entry> entries = new ArrayList<>();
        for (String line : Files.readAllLines(folder().resolve("SOURCES.md"), StandardCharsets.UTF_8)) {
            if (line.startsWith("| `")) {
                Matcher row = ROW.matcher(line.strip());
                assertThat(row.matches()).as("a well-formed SOURCES.md row: %s", line).isTrue();
                entries.add(new Entry(row.group(1), row.group(2), row.group(3), row.group(4),
                        LocalDate.parse(row.group(5)), row.group(6)));
            }
        }
        return entries;
    }

    private static String prefix(SoundCategory category) {
        return category.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    @Test
    void everyCategoryHasAtLeastEightSlicesAndTheBankLoadsThemAll() throws Exception {
        SampleBank bank = SampleBank.builtIn();

        Map<SoundCategory, Integer> onDisk = new TreeMap<>();
        for (String file : slicesOnDisk()) {
            SoundCategory category = Stream.of(SoundCategory.values())
                    .filter(c -> file.matches(prefix(c) + "-\\d{2}\\.wav"))
                    .findFirst().orElseThrow(() -> new AssertionError("no category is named by " + file));
            onDisk.merge(category, 1, Integer::sum);
        }
        for (SoundCategory category : SoundCategory.values()) {
            assertThat(onDisk.getOrDefault(category, 0)).as("slices of %s", category).isGreaterThanOrEqualTo(8);
            assertThat(bank.slices(category)).as("slices of %s the bank loaded", category)
                    .hasSize(onDisk.get(category));
            for (int n = 1; n <= onDisk.get(category); n++) {
                assertThat(folder().resolve("%s-%02d.wav".formatted(prefix(category), n))).as("numbered with no gaps")
                        .exists();
            }
        }
    }

    @Test
    void everySliceIsAMono16Bit48KilohertzPcmWavOfOneShortImpact() throws Exception {
        for (String file : slicesOnDisk()) {
            AudioFormat format;
            long frames;
            try (AudioInputStream in = AudioSystem.getAudioInputStream(folder().resolve(file).toFile())) {
                format = in.getFormat();
                frames = in.getFrameLength();
            } catch (UnsupportedAudioFileException | IOException e) {
                throw new AssertionError(file + " isn't a WAV javax.sound can read", e);
            }
            assertThat(AudioSystem.getAudioFileFormat(folder().resolve(file).toFile()).getType())
                    .isEqualTo(javax.sound.sampled.AudioFileFormat.Type.WAVE);
            assertThat(format.getEncoding()).as(file).isEqualTo(AudioFormat.Encoding.PCM_SIGNED);
            assertThat(format.getChannels()).as(file).isEqualTo(1);
            assertThat(format.getSampleSizeInBits()).as(file).isEqualTo(16);
            assertThat(format.getSampleRate()).as(file).isEqualTo(48_000f);
            assertThat(format.isBigEndian()).as(file).isFalse();
            // One impact: at least 20 ms and well under a second.
            assertThat(frames).as(file).isBetween(960L, 24_000L);
        }
    }

    @Test
    void sourcesMdAndTheSlicesMatchBothWays() throws Exception {
        List<String> listed = entries().stream().map(Entry::file).toList();

        assertThat(listed).as("listed once each").doesNotHaveDuplicates();
        assertThat(listed).as("every slice listed, and nothing listed that isn't a slice")
                .containsExactlyInAnyOrderElementsOf(slicesOnDisk());
    }

    @Test
    void everyEntryGivesItsPerFileSourceUrlAuthorCc0LicenceAndRetrievalDate() throws Exception {
        for (Entry entry : entries()) {
            Matcher source = SOURCE.matcher(entry.url());
            assertThat(source.matches()).as("%s: a Freesound per-file URL or Kenney's page, not %s", entry.file(),
                    entry.url()).isTrue();
            assertThat(entry.author()).as(entry.file()).isNotBlank();
            assertThat(entry.licence()).as(entry.file()).isEqualTo("CC0 1.0");
            assertThat(entry.retrieved()).as(entry.file()).isBefore(LocalDate.of(2027, 1, 1))
                    .isAfter(LocalDate.of(2026, 1, 1));
            if (source.group(1) != null) {
                // Freesound's own download names start with the sound's id: the cut is from the URL's sound.
                assertThat(entry.cutFrom()).as(entry.file()).startsWith("`" + source.group(1) + "__");
            }
        }
    }
}
