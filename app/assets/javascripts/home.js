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

document.addEventListener('DOMContentLoaded', () => {
  const body = document.body;
  const arenas = document.getElementById('arenas');
  const maybeSummaryUrl = body.dataset.summaryurl;
  const arenaTemplate = document.getElementById('arena-template');

  if (!!window.EventSource && !!maybeSummaryUrl) {
    function eventSource() {
      const stringSource = new EventSource(maybeSummaryUrl);
      stringSource.onopen = () => { };
      stringSource.onmessage = (message) => {
        const data = JSON.parse(message.data);
        //console.log(data);

        for (const arenaId in data) {
          const maybeArenaDiv = document.getElementById(arenaId);
          const arenaData = data[arenaId];

          if (maybeArenaDiv == null) {
            const newArena = arenaTemplate.content.firstElementChild.cloneNode(true);
            newArena.id = arenaId;
            arenas.appendChild(newArena);
          }

          const arenaDiv = document.getElementById(arenaId);

          arenaDiv.getElementsByClassName("name")[0].innerText = arenaData.name;

          arenaDiv.getElementsByClassName("num-players")[0].innerText = arenaData.numPlayers;

          if (arenaData.numPlayers > 0) {
            arenaDiv.getElementsByClassName("banner")[0].innerText = 'Top Players:';
          }
          else {
            arenaDiv.getElementsByClassName("banner")[0].innerText = 'No Players Yet';
          }

          const topPlayers = arenaDiv.getElementsByClassName("top-players")[0];

          topPlayers.innerHTML = '';

          arenaData.topPlayers.forEach( (player) => {
            const playerDiv = document.createElement('div');
            playerDiv.classList.add('score');

            const picDiv = document.createElement('div');
            const pic = document.createElement('img');
            pic.src = player.pic
            picDiv.appendChild(pic);
            playerDiv.appendChild(picDiv);

            const name = document.createElement('span');
            name.classList.add('name');
            name.innerText = player.name;
            playerDiv.appendChild(name);

            const score = document.createElement('span');
            score.classList.add('num');
            score.innerText = player.score;
            playerDiv.appendChild(score);

            topPlayers.appendChild(playerDiv);
          });

          arenaDiv.getElementsByClassName("watch")[0].action = '/' + arenaId;

          if (arenaData.joinable) {
            arenaDiv.getElementsByClassName("join")[0].action = '/' + arenaId + '/join';
            arenaDiv.getElementsByClassName("join")[0].style['display'] = 'block';
          } else {
            arenaDiv.getElementsByClassName("join")[0].style['display'] = 'none';
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


