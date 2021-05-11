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

const println = console.log;

const maxPlayersPerRoom = 2;

document.addEventListener('DOMContentLoaded', () => {
  const body = document.body;
  const arenas = document.getElementById('arenas');
  const maybeSummaryUrl = body.dataset.summaryurl;

  if (!!window.EventSource && !!maybeSummaryUrl) {
    function eventSource() {
      const stringSource = new EventSource(maybeSummaryUrl);
      stringSource.onopen = () => { };
      stringSource.onmessage = (message) => {
        const data = JSON.parse(message.data);
        //println(data);

        function isFull(child) {
          const maybeData = data[child.id];

          if (maybeData !== undefined) {
            return (maybeData.numPlayers >= maxPlayersPerRoom);
          }
          else {
            return false;
          }
        }

        for (let i = 0; i < arenas.children.length; i++) {
          const thisChild = arenas.children[i];
          const path = thisChild.id;
          const prevChild = (i === 0) ? null : arenas.children[i - 1];

          if ((prevChild == null) || (isFull(prevChild))) {
            const maybeData = data[path];
            if (maybeData !== undefined) {
              if (document.getElementById(`${path}-name`).innerText !== maybeData.name) {
                document.getElementById(`${path}-name`).innerText = maybeData.name;
              }

              if (document.getElementById(`${path}-numPlayers`).innerText !== maybeData.numPlayers) {
                document.getElementById(`${path}-numPlayers`).innerText = maybeData.numPlayers;
              }

              if (isFull(thisChild)) {
                document.getElementById(`${path}-full`).innerText = 'FULL';
                document.getElementById(`${path}-join`).style.display = 'none';
              }

              const topPlayers = document.getElementById(`${path}-topPlayers`)
              topPlayers.innerHTML = '';

              maybeData.topPlayers.forEach( (player) => {
                const li = document.createElement('li');
                li.innerText = `${player.name} - ${player.score}`;
                topPlayers.appendChild(li);
              });

              thisChild.style.display = 'block';
            }
          }
        }
      };

      stringSource.onerror = (event) => {
        console.error(event);

        // the browser reconnects automatically in some cases, but if it doesn't, then we do it manually
        if (event.target.readyState === EventSource.CLOSED) {
          // try to reconnect
          window.setTimeout(eventSource, 5000);
        }
      };
    }

    eventSource();

  }
  else {
    // todo
  }

});


