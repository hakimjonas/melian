package net.ghoula.melian.server

import munit.FunSuite

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import scala.util.Try

import net.ghoula.eru.Eru
import net.ghoula.eru.EruRuntime
import net.ghoula.eru.http.*
import net.ghoula.eru.http.server.HttpServerConfig
import net.ghoula.melian.Ok
import net.ghoula.melian.router.Router

/** Wires [[MelianServer.withAcme]] end-to-end against a pre-provisioned certificate store: the
  * provisioner's reuse path serves the stored PKCS12 without ACME traffic, the TLS server binds,
  * and the HTTP-01 challenge listener comes up alongside it. The full issuance flow is covered by
  * eru-http-acme's own integration spec; what this pins is Melian's wiring (DESIGN.md 14.1).
  */
class AcmeProvisioningSpec extends FunSuite {

  given runtime: EruRuntime = EruRuntime.shared

  override def afterAll(): Unit = {
    Try(EruRuntime.shared.cleanup()): Unit
    super.afterAll()
  }

  private val Domain = "issued.example.com"

  /** Pre-provisions `storePath/<domain>/<domain>.p12` + `cert.pem` with a keytool-generated
    * self-signed leaf, so the provisioner reuses it instead of talking ACME.
    */
  private def preProvision(storePath: Path): Unit = {
    val dir = storePath.resolve(Domain)
    Files.createDirectories(dir)
    val p12 = dir.resolve(s"$Domain.p12")
    val gen = ProcessBuilder(
      "keytool",
      "-genkeypair",
      "-alias",
      Domain,
      "-keyalg",
      "EC",
      "-groupname",
      "secp256r1",
      "-dname",
      s"CN=$Domain",
      "-keypass",
      "changeit",
      "-storepass",
      "changeit",
      "-storetype",
      "PKCS12",
      "-keystore",
      p12.toString,
      "-validity",
      "90"
    ).redirectErrorStream(true).start()
    val out = gen.getInputStream.readAllBytes()
    assert(gen.waitFor(60, TimeUnit.SECONDS), "keytool genkeypair did not finish")
    assertEquals(gen.exitValue(), 0, s"keytool genkeypair failed:\n${new String(out)}")

    val exportPath = dir.resolve("stub.der")
    val exp = ProcessBuilder(
      "keytool",
      "-exportcert",
      "-alias",
      Domain,
      "-keypass",
      "changeit",
      "-storepass",
      "changeit",
      "-storetype",
      "PKCS12",
      "-keystore",
      p12.toString,
      "-rfc",
      "-file",
      exportPath.toString
    ).redirectErrorStream(true).start()
    val expOut = exp.getInputStream.readAllBytes()
    assert(exp.waitFor(60, TimeUnit.SECONDS), "keytool export did not finish")
    assertEquals(exp.exitValue(), 0, s"keytool export failed:\n${new String(expOut)}")
    Files.move(exportPath, dir.resolve("cert.pem")): Unit
  }

  test("withAcme: reuse path serves TLS and the challenge listener without ACME traffic") {
    val storePath = Files.createTempDirectory("melian-acme")
    preProvision(storePath)

    val settings = MelianServer.AcmeSettings(
      domain = Domain,
      contactEmail = "ops@example.com",
      staging = true,
      storePath = storePath,
      http01Port = 0,
      // Unreachable on purpose: reuse must never dial the directory.
      directoryUrl = Some("http://localhost:1/directory")
    )

    val router = Router.builder
      .get("/healthz", () => Eru.succeed(Ok("ok")))
      .build
      .getOrElse(fail("router build failed"))

    val result = MelianServer.withAcme(settings, HttpServerConfig.localhost.withPort(0), router) { (server, handle) =>
      for {
        addr <- server.start
        _ <- Eru.effect {
          assert(addr.port > 0, "TLS server must bind")
          assert(handle.challengeAddress.port > 0, "challenge listener must bind")
          assert(Files.exists(storePath.resolve(Domain).resolve(s"$Domain.p12")))
        }
          .mapError(e => HttpError.NetworkError(e.getMessage, None))
        _ <- handle.provisioner.stop()
      } yield addr
    }

    val addr = result.unsafeRunSync()
    assert(addr.port > 0)
  }
}
