package mesosphere.marathon
package api.akkahttp

import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.server.PathMatcher.{ Matched, Unmatched }
import akka.http.scaladsl.server.PathMatchers.Slash
import akka.http.scaladsl.testkit.ScalatestRouteTest
import mesosphere.UnitTest
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.test.GroupCreation

class PathMatchersTest extends UnitTest with GroupCreation with ScalatestRouteTest {
  import PathMatchers._
  import PathId.StringPathId

  class PathMatchersTestFixture {
    val app1 = AppDefinition("/test/group1/app1".toPath)
    val app2 = AppDefinition("/test/group2/app2".toPath)
    val app3 = AppDefinition("/test/group2/restart".toPath)
    val rootGroup = createRootGroup(
      groups = Set(
        createGroup("/test".toPath, groups = Set(
          createGroup("/test/group1".toPath, Map(app1.id -> app1)),
          createGroup("/test/group2".toPath, Map(app2.id -> app2)),
          createGroup("/test/group2".toPath, Map(app3.id -> app3))
        ))))

  }

  "ExistingAppPathId matcher" should {

    "not match groups" in new PathMatchersTestFixture {
      ExistingAppPathId(() => rootGroup)(Path("test/group1")) shouldBe Unmatched
    }

    "match apps that exist" in new PathMatchersTestFixture {
      ExistingAppPathId(() => rootGroup)(Path("test/group1/app1")) shouldBe Matched(Path(""), Tuple1("/test/group1/app1".toPath))
    }

    "match not match apps that don't exist" in new PathMatchersTestFixture {
      ExistingAppPathId(() => rootGroup)(Path("test/group1/app3")) shouldBe Unmatched
    }

    "leave path components after matching appIds unconsumed" in new PathMatchersTestFixture {
      ExistingAppPathId(() => rootGroup)(Path("test/group1/app1/restart/ponies")) shouldBe Matched(Path("/restart/ponies"), Tuple1("/test/group1/app1".toPath))
    }

    "match apps that contains 'restart'" in new PathMatchersTestFixture {
      ExistingAppPathId(() => rootGroup)(Path("test/group2/restart/restart")) shouldBe Matched(Path("/restart"), Tuple1("/test/group2/restart".toPath))
    }
  }

  "AppPathIdLike matcher" should {
    "stop matching when it reaches a Marathon API keyword" in {
      AppPathIdLike(Path("test/group/restart/ponies")) shouldBe Matched(Path("/restart/ponies"), Tuple1("/test/group".toPath))
    }

    "match all the way to to the end" in {
      AppPathIdLike(Path("test/group1/app1")) shouldBe Matched(Path.Empty, Tuple1("/test/group1/app1".toPath))
    }

    "considers empty paths as non-matches" in {
      AppPathIdLike(Path("/")) shouldBe Unmatched
    }

    "considers it an unmatch if path starts with keyword" in {
      AppPathIdLike(Path("/restart")) shouldBe Unmatched
    }
  }
}
