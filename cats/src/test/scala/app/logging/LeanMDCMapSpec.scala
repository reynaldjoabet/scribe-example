package app.logging

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scribe.mdc.*

class LeanMDCMapSpec extends AnyWordSpec with Matchers {
  private def values(mdc: MDC): Map[String, Any] =
    mdc.map.map((k, v) => k -> v())

  "LeanMDCMap" should {
    "behave like Scribe's MDCMap" in {
      val mdc = new LeanMDCMap(None)
      mdc.map shouldBe empty
      mdc.update("tenant", "acme"): Unit
      values(mdc) shouldBe Map("tenant" -> "acme")
      mdc.contains("tenant") shouldBe true
      mdc.context("tenant" -> "other")(values(mdc)) shouldBe Map(
        "tenant" -> "other"
      )
      values(mdc) shouldBe Map("tenant" -> "acme")
      mdc.remove("tenant") shouldBe Some("acme")
      mdc.map shouldBe empty
    }
    "fall back to its parent for get, like MDCMap" in {
      val parent = new LeanMDCMap(None)
      parent.update("region", "eu"): Unit
      val child = new LeanMDCMap(Some(parent))
      child.get("region").map(_()) shouldBe Some("eu")
      child.map shouldBe empty
    }
  }
}
