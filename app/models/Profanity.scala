/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package models

import play.api.Environment

import javax.inject.{
  Inject,
  Singleton,
}

trait Profanity {
  def matches(s: String): Boolean
}

@Singleton
class ProfanityLive @Inject() (environment: Environment) extends Profanity {

  private val words: Set[String] = environment.resourceAsStream("google-profanity-words/list.txt").map { inputStream =>
    scala.io.Source.fromInputStream(inputStream).getLines().toSet
  }.getOrElse(throw new Exception("google-profanity-words/list.txt not found"))

  def matches(s: String): Boolean = {
    val clean = s.takeWhile(_.isLetterOrDigit).toLowerCase
    words.exists(clean.contains)
  }

}