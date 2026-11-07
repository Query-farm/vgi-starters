// Entry point.
//
//   vgi-starter-worker                      # stdio: what DuckDB launches by default
//   vgi-starter-worker --http --port 8000   # HTTP: for Docker / hosted deploys
//   vgi-starter-worker --unix /tmp/w.sock   # AF_UNIX: DuckDB's launch: pool
//   vgi-starter-worker --debug              # debug logging (stderr)
//
// Attach it from DuckDB:
//
//   ATTACH 'starter' (TYPE vgi, LOCATION '/abs/path/to/vgi-starter-worker');

using Starter.Worker;

var level = "Warning";
var rest = new List<string>();
for (var i = 0; i < args.Length; i++)
{
    switch (args[i])
    {
        case "--debug":
            level = "Debug";
            break;
        case "--log-level" when i + 1 < args.Length:
            level = args[++i];
            break;
        default:
            rest.Add(args[i]);
            break;
    }
}

// stdout carries the protocol (stdio) or the PORT:<n> line (HTTP): every log line goes
// to stderr. The HTTP host reads these settings from the environment.
Environment.SetEnvironmentVariable("Logging__Console__LogToStandardErrorThreshold", "Trace");
Environment.SetEnvironmentVariable("Logging__LogLevel__Default", level);

await StarterWorker.Build().RunFromArgsAsync([.. rest]);
