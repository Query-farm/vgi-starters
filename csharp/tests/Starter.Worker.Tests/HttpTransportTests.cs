// The built worker serves HTTP: `--http --port 0` prints PORT:<n> on stdout and answers /health.

using System.Diagnostics;
using Xunit;

namespace Starter.Worker.Tests;

public class HttpTransportTests
{
    [Fact]
    public async Task HttpPrintsPortAndServesHealth()
    {
        var dll = Path.Combine(AppContext.BaseDirectory, "vgi-starter-worker.dll");
        var start = new ProcessStartInfo(Environment.GetEnvironmentVariable("DOTNET_HOST_PATH") ?? "dotnet")
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        foreach (var arg in new[] { dll, "--http", "--port", "0" })
        {
            start.ArgumentList.Add(arg);
        }

        using var worker = Process.Start(start)!;
        try
        {
            var line = await worker.StandardOutput.ReadLineAsync(TestContext.Current.CancellationToken)
                .AsTask().WaitAsync(TimeSpan.FromSeconds(60), TestContext.Current.CancellationToken);
            Assert.NotNull(line);
            Assert.StartsWith("PORT:", line, StringComparison.Ordinal);
            using var http = new HttpClient();
            var response = await http.GetAsync(new Uri($"http://127.0.0.1:{line["PORT:".Length..]}/health"), TestContext.Current.CancellationToken);
            Assert.True(response.IsSuccessStatusCode, $"/health returned {(int)response.StatusCode}");
        }
        finally
        {
            worker.Kill(entireProcessTree: true);
            await worker.WaitForExitAsync(TestContext.Current.CancellationToken);
        }
    }
}
