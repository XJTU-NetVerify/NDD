package org.ants.javandd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TracingFactoryTest {
  @TempDir Path directory;

  @Test
  void savedTraceCanBeReplayed() throws IOException, ClassNotFoundException {
    TracingFactory recording = (TracingFactory) TracingFactory.init(1000, 100);
    recording.setVarNum(2);
    BDD x = recording.ithVar(0);
    BDD y = recording.ithVar(1);
    BDD result = x.and(y);
    result.free();
    x.free();
    y.free();

    Path path = directory.resolve("operations.gz");
    recording.saveTrace(path);
    BDDFactory replay = JFactory.init(1000, 100);
    TracingFactory.replayTrace(replay, path);
    assertEquals(2, replay.varNum());
  }

  @Test
  void rejectsTraceThatIsNotAList() throws IOException {
    Path path = writeTrace("not a trace");
    IOException error = assertThrows(IOException.class,
        () -> TracingFactory.replayTrace(JFactory.init(1000, 100), path));
    assertEquals("Invalid trace: expected a list of operations", error.getMessage());
  }

  @Test
  void rejectsTraceWithAnInvalidEntry() throws IOException {
    Path path = writeTrace(Collections.singletonList("not an operation"));
    IOException error = assertThrows(IOException.class,
        () -> TracingFactory.replayTrace(JFactory.init(1000, 100), path));
    assertEquals("Invalid trace: expected a TracedOperation entry", error.getMessage());
  }

  private Path writeTrace(Object contents) throws IOException {
    Path path = directory.resolve("invalid.gz");
    try (ObjectOutputStream output = new ObjectOutputStream(
        new GZIPOutputStream(Files.newOutputStream(path)))) {
      output.writeObject(contents);
    }
    return path;
  }
}
