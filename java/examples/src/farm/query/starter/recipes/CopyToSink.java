package farm.query.starter.recipes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import farm.query.vgi.Worker;
import farm.query.vgi.buffering.TableBufferingCombineParams;
import farm.query.vgi.buffering.TableBufferingProcessParams;
import farm.query.vgi.function.Arguments;
import farm.query.vgi.function.FunctionMetadata;
import farm.query.vgi.function.FunctionSpec;
import farm.query.vgi.storage.FunctionStorage;
import farm.query.vgi.table.CopyToFunction;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;

/**
 * Recipe: write query results out with {@code COPY ... TO} and a custom format.
 *
 * <pre>
 *   ATTACH 'copy_to_sink' (TYPE vgi, LOCATION '.../bin/recipes copy_to_sink');
 *   COPY (SELECT * FROM t) TO 'out.jsonl' (FORMAT 'copy_to_sink.jsonl_out');
 * </pre>
 *
 * <p>The format name is qualified by the attach alias. {@code write} runs once per batch (possibly
 * in parallel, possibly in different processes), so it stashes rows in {@code params.storage()};
 * {@code close} runs once and writes the file. Swap the file write for an upload to object storage,
 * a warehouse load, or an API call.
 */
public final class CopyToSink extends CopyToFunction {

  private static final byte[] NS = "shards".getBytes(StandardCharsets.UTF_8);
  private static final byte[] KEY = new byte[0];
  private static final ObjectMapper JSON = new ObjectMapper();

  private static final FunctionSpec SPEC =
      new FunctionSpec(
          "write_json_lines",
          FunctionMetadata.describe("Write each row as one JSON object per line"),
          List.of());

  @Override
  public FunctionSpec spec() {
    return SPEC;
  }

  @Override
  public String copyToFormat() {
    return "jsonl_out";
  }

  @Override
  public String copyToComment() {
    return "Newline-delimited JSON, one object per row";
  }

  /** Serialize this batch and stash it (scoped to this COPY by its execution id). */
  @Override
  public void write(
      VectorSchemaRoot batch,
      Arguments options,
      String filePath,
      TableBufferingProcessParams params) {
    List<String> lines = new ArrayList<>();
    for (int r = 0; r < batch.getRowCount(); r++) {
      Map<String, Object> row = new LinkedHashMap<>();
      for (FieldVector v : batch.getFieldVectors()) {
        Object value = v.getObject(r);
        row.put(v.getName(), value instanceof Number || value == null ? value : value.toString());
      }
      try {
        lines.add(JSON.writeValueAsString(row));
      } catch (JsonProcessingException e) {
        throw new IllegalStateException(e);
      }
    }
    if (!lines.isEmpty()) {
      params
          .storage()
          .stateAppend(NS, KEY, String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }
  }

  /** Write every stashed batch to the file once; return the row count. */
  @Override
  public long close(Arguments options, String filePath, TableBufferingCombineParams params) {
    long written = 0;
    try (Writer out = Files.newBufferedWriter(Path.of(filePath), StandardCharsets.UTF_8)) {
      for (FunctionStorage.LogEntry chunk : params.storage().stateLogScan(NS, KEY, -1L, 0)) {
        String text = new String(chunk.value(), StandardCharsets.UTF_8);
        out.write(text);
        out.write('\n');
        written += text.chars().filter(c -> c == '\n').count() + 1;
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write " + filePath, e);
    }
    return written;
  }

  static Worker worker() {
    return Worker.builder().catalogName("copy_to_sink").registerTableBuffering(new CopyToSink());
  }
}
