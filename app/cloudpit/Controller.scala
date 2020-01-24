/*
 * Copyright 2019 Google LLC
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

package cloudpit

import javax.inject.Inject
import play.api.mvc.InjectedController

import scala.concurrent.{ExecutionContext, Future}

class Controller @Inject()(implicit ec: ExecutionContext) extends InjectedController {

  if (false) {
    ec
  }

  def index = Action.async {
    /*
    dao.arenas().map { arenas =>
      Ok(Json.toJson(arenas))
    }
     */
    Future.successful(NotImplemented)
  }

}
