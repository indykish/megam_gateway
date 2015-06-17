/* 
 ** Copyright [2013-2014] [Megam Systems]
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
** http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package models.tosca

import scalaz._
import scalaz.syntax.SemigroupOps
import scalaz.NonEmptyList._
import scalaz.effect.IO
import scalaz.Validation._
import scalaz.EitherT._
import Scalaz._
import org.megam.util.Time
import scala.collection.mutable.ListBuffer
import controllers.stack._
import controllers.Constants._
import controllers.funnel.FunnelErrors._
import models.tosca._
import models._
import models.riak._
import models.cache._
import com.stackmob.scaliak._
import com.basho.riak.client.core.query.indexes.{ RiakIndexes, StringBinIndex, LongIntIndex }
import com.basho.riak.client.core.util.{ Constants => RiakConstants }
import org.megam.common.riak.{ GSRiak, GunnySack }
import org.megam.common.uid.UID
import net.liftweb.json._
import net.liftweb.json.scalaz.JsonScalaz._
import java.nio.charset.Charset

/**
 * @author rajthilak
 *
 */
case class AssembliesInput(name: String, assemblies: models.tosca.AssembliesList, inputs: AssembliesInputs) {
  val json = "{\"name\":\"" + name + "\", \"assemblies\":" + AssembliesList.toJson(assemblies, true) + ",\"inputs\":" + inputs.json + "}"
}

case class CloudSetting(id: String, cstype: String, cloudsettings: String, x: String, y: String, z: String, wires: models.tosca.CSWiresList) {
  val json = "{\"id\":\"" + id + "\",\"cstype\":\"" + cstype + "\",\"cloudsettings\":\"" + cloudsettings + "\",\"x\":\"" + x + "\",\"y\":\"" + y + "\",\"z\":\"" + z + "\",\"wires\":" + CSWiresList.toJson(wires, true) + "}"

  def toJValue: JValue = {
    import net.liftweb.json.scalaz.JsonScalaz.toJSON
    val preser = new models.json.tosca.CloudSettingSerialization()
    toJSON(this)(preser.writer)
  }

  def toJson(prettyPrint: Boolean = false): String = if (prettyPrint) {
    pretty(render(toJValue))
  } else {
    compactRender(toJValue)
  }
}

object CloudSetting {
  def empty: CloudSetting = new CloudSetting(new String(), new String(), new String, new String(), new String, new String(), CSWiresList.empty)

  def fromJValue(jValue: JValue)(implicit charset: Charset = UTF8Charset): Result[CloudSetting] = {
    import net.liftweb.json.scalaz.JsonScalaz.fromJSON
    val preser = new models.json.tosca.CloudSettingSerialization()
    fromJSON(jValue)(preser.reader)
  }

  def fromJson(json: String): Result[CloudSetting] = (Validation.fromTryCatch {
    play.api.Logger.debug(("%-20s -->[%s]").format("---json------------------->", json))
    parse(json)
  } leftMap { t: Throwable =>
    UncategorizedError(t.getClass.getCanonicalName, t.getMessage, List())
  }).toValidationNel.flatMap { j: JValue => fromJValue(j) }
}

case class AssembliesInputs(id: String, assemblies_type: String, label: String, cloudsettings: models.tosca.CloudSettingsList) {
  val json = "{\"id\": \"" + id + "\", \"assemblies_type\":\"" + assemblies_type + "\",\"label\" : \"" + label + "\",\"cloudsettings\":" + CloudSettingsList.toJson(cloudsettings, true) + "}"
}

object AssembliesInputs {
  def empty: AssembliesInputs = new AssembliesInputs(new String(), new String(), new String, models.tosca.CloudSettingsList.emptyRR)
}

case class AssembliesResult(id: String, accounts_id: String, name: String, assemblies: models.tosca.AssemblyLinks, inputs: AssembliesInputs, created_at: String) {

  def toJValue: JValue = {
    import net.liftweb.json.scalaz.JsonScalaz.toJSON
    import models.json.tosca.AssembliesResultSerialization
    val preser = new AssembliesResultSerialization()
    toJSON(this)(preser.writer)
  }

  def toJson(prettyPrint: Boolean = false): String = if (prettyPrint) {
    pretty(render(toJValue))
  } else {
    compactRender(toJValue)
  }
}

object AssembliesResult {

  def fromJValue(jValue: JValue)(implicit charset: Charset = UTF8Charset): Result[AssembliesResult] = {
    import net.liftweb.json.scalaz.JsonScalaz.fromJSON
    import models.json.tosca.AssembliesResultSerialization
    val preser = new AssembliesResultSerialization()
    fromJSON(jValue)(preser.reader)
  }

  def fromJson(json: String): Result[AssembliesResult] = (Validation.fromTryCatch {
    parse(json)
  } leftMap { t: Throwable =>
    UncategorizedError(t.getClass.getCanonicalName, t.getMessage, List())
  }).toValidationNel.flatMap { j: JValue => fromJValue(j) }

}

object Assemblies {

  implicit val formats = DefaultFormats
  private val riak = GWRiak("assemblies")

  val metadataKey = "assemblies"
  val metadataVal = "Assemblies Creation"
  val bindex = "assemblies"

  /**
   * A private method which chains computation to make GunnySack when provided with an input json, email.
   * parses the json, and converts it to nodeinput, if there is an error during parsing, a MalformedBodyError is sent back.
   * After that flatMap on its success and the account id information is looked up.
   * If the account id is looked up successfully, then yield the GunnySack object.
   */
  private def mkGunnySack(email: String, input: String): ValidationNel[Throwable, Option[GunnySack]] = {
    play.api.Logger.debug(("%-20s -->[%s]").format("tosca.Assemblies", "mkGunnySack:Entry"))
    play.api.Logger.debug(("%-20s -->[%s]").format("email", email))
    play.api.Logger.debug(("%-20s -->[%s]").format("json", input))

    val ripNel: ValidationNel[Throwable, AssembliesInput] = (Validation.fromTryCatch {
      parse(input).extract[AssembliesInput]
    } leftMap { t: Throwable => new MalformedBodyError(input, t.getMessage) }).toValidationNel //capture failure

    for {
      rip <- ripNel
      aor <- (Accounts.findByEmail(email) leftMap { t: NonEmptyList[Throwable] => t })
      aem <- (AssembliesList.createLinks(email, rip.assemblies) leftMap { t: NonEmptyList[Throwable] => t })
      uir <- (UID(MConfig.snowflakeHost, MConfig.snowflakePort, "ams").get leftMap { ut: NonEmptyList[Throwable] => ut })
      req <- (Requests.createforNewNode("{\"node_id\": \"" + (uir.get._1 + uir.get._2) + "\",\"node_name\": \"" + rip.name + "\",\"req_type\": \"create\"}") leftMap { t: NonEmptyList[Throwable] => t })
    } yield {
      val bvalue = Set(aor.get.id)
      var assembly_links = new ListBuffer[String]()
      for (assembly <- aem) {
        assembly match {
          case Some(value) => assembly_links += value.id
          case None        => assembly_links += ""
        }
      }
      val json = new AssembliesResult(uir.get._1 + uir.get._2, aor.get.id, rip.name, assembly_links.toList, rip.inputs, Time.now.toString).toJson(false)
      new GunnySack((uir.get._1 + uir.get._2), json, RiakConstants.CTYPE_TEXT_UTF8, None,
        Map(metadataKey -> metadataVal), Map((bindex, bvalue))).some
    }
  }

  /*
   * create new market place item with the 'name' of the item provide as input.
   * A index name assemblies name will point to the "csars" bucket
   */
  def create(email: String, input: String): ValidationNel[Throwable, Option[AssembliesResult]] = {
    play.api.Logger.debug(("%-20s -->[%s]").format("tosca.Assemblies", "create:Entry"))
    play.api.Logger.debug(("%-20s -->[%s]").format("email", email))
    play.api.Logger.debug(("%-20s -->[%s]").format("yaml", input))

    (mkGunnySack(email, input) leftMap { err: NonEmptyList[Throwable] =>
      new ServiceUnavailableError(input, (err.list.map(m => m.getMessage)).mkString("\n"))
    }).toValidationNel.flatMap { gs: Option[GunnySack] =>

      (riak.store(gs.get) leftMap { t: NonEmptyList[Throwable] => t }).
        flatMap { maybeGS: Option[GunnySack] =>
          maybeGS match {
            case Some(thatGS) => (parse(thatGS.value).extract[AssembliesResult].some).successNel[Throwable]
            case None => {
              play.api.Logger.warn(("%-20s -->[%s]").format("Assemblies.created success", "Scaliak returned => None. Thats OK."))
              (parse(gs.get.value).extract[AssembliesResult].some).successNel[Throwable];
            }
          }
        }
    }
  }

  def findById(assembliesID: Option[List[String]]): ValidationNel[Throwable, AssembliesResults] = {
    play.api.Logger.debug(("%-20s -->[%s]").format("models.Assemblies", "findByNodeName:Entry"))
    play.api.Logger.debug(("%-20s -->[%s]").format("nodeNameList", assembliesID))
    (assembliesID map {
      _.map { asm_id =>
        play.api.Logger.debug(("%-20s -->[%s]").format("Assemblies ID", asm_id))
        (riak.fetch(asm_id) leftMap { t: NonEmptyList[Throwable] =>
          new ServiceUnavailableError(asm_id, (t.list.map(m => m.getMessage)).mkString("\n"))
        }).toValidationNel.flatMap { xso: Option[GunnySack] =>
          xso match {
            case Some(xs) => {
              //JsonScalaz.Error doesn't descend from java.lang.Error or Throwable. Screwy.
              (AssembliesResult.fromJson(xs.value) leftMap
                { t: NonEmptyList[net.liftweb.json.scalaz.JsonScalaz.Error] =>
                  JSONParsingError(t)
                }).toValidationNel.flatMap { j: AssembliesResult =>
                  play.api.Logger.debug(("%-20s -->[%s]").format("assemblies result", j))
                  Validation.success[Throwable, AssembliesResults](nels(j.some)).toValidationNel //screwy kishore, every element in a list ? 
                }
            }
            case None => {
              Validation.failure[Throwable, AssembliesResults](new ResourceItemNotFound(asm_id, "")).toValidationNel
            }
          }
        }
      } // -> VNel -> fold by using an accumulator or successNel of empty. +++ => VNel1 + VNel2
    } map {
      _.foldRight((AssembliesResults.empty).successNel[Throwable])(_ +++ _)
    }).head //return the folded element in the head. 
  }

  /*
   * An IO wrapped finder using an email. Upon fetching the account_id for an email, 
   * the csarnames are listed on the index (account.id) in bucket `CSARs`.
   * Using a "csarname" as key, return a list of ValidationNel[List[CSARResult]] 
   * Takes an email, and returns a Future[ValidationNel, List[Option[CSARResult]]]
   */
  def findByEmail(email: String): ValidationNel[Throwable, AssembliesResults] = {
    play.api.Logger.debug(("%-20s -->[%s]").format("tosca.Assemblies", "findByEmail:Entry"))
    play.api.Logger.debug(("%-20s -->[%s]").format("email", email))
    val res = eitherT[IO, NonEmptyList[Throwable], ValidationNel[Throwable, AssembliesResults]] {
      (((for {
        aor <- (Accounts.findByEmail(email) leftMap { t: NonEmptyList[Throwable] => t }) //captures failure on the left side, success on right ie the component before the (<-)
      } yield {
        val bindex = ""
        val bvalue = Set("")
        play.api.Logger.debug(("%-20s -->[%s]").format("tosca.Assemblies", "findByEmail" + aor.get.id))
        new GunnySack("assemblies", aor.get.id, RiakConstants.CTYPE_TEXT_UTF8,
          None, Map(metadataKey -> metadataVal), Map((bindex, bvalue))).some
      }) leftMap { t: NonEmptyList[Throwable] => t } flatMap {
        gs: Option[GunnySack] => riak.fetchIndexByValue(gs.get)
      } map { nm: List[String] =>
        (if (!nm.isEmpty) findById(nm.some) else
          new ResourceItemNotFound(email, "Assemblies = nothing found for the user.").failureNel[AssembliesResults])
      }).disjunction).pure[IO]
    }.run.map(_.validation).unsafePerformIO
    res.getOrElse(new ResourceItemNotFound(email, "Assemblies = nothing found for the users.").failureNel[AssembliesResults])
  }

}