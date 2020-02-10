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

function clear(parent, maybeChildClassName, removeChild) {
  if (maybeChildClassName === undefined) {
    while (parent.firstChild) {
      parent.removeChild(parent.firstChild);
    }
  }
  else {
    Array.prototype.slice.call(parent.getElementsByClassName(maybeChildClassName)).forEach( child => {
      if (removeChild === true)
        parent.removeChild(child);
      else
        clear(child);
    });
  }
}

document.addEventListener('DOMContentLoaded', () => {

  const body = document.querySelector('body');

  const maybeUpdatesUrl = body.dataset.updatesurl;

  if (!!window.EventSource && !!maybeUpdatesUrl) {
    const title = document.getElementById('title');
    const arena = document.getElementById('arena');
    const scoreboard = document.getElementById('scoreboard');

    const stringSource = new EventSource(maybeUpdatesUrl);
    stringSource.onopen = () => { };
    stringSource.onmessage = (message) => {
      if (body.dataset.paused !== 'true') {
        const data = JSON.parse(message.data);
        //console.log(data);

        title.textContent = data.name;

        // arena setup
        if (parseInt(arena.dataset.width) !== data.width || parseInt(arena.dataset.height) !== data.height) {
          // dims changed
          arena.dataset.width = data.width;
          arena.dataset.height = data.height;

          // arena setup
          clear(arena);

          arena.style.gridTemplateColumns = 'repeat(' + data.width + ', 1fr)';

          for (let y = 0; y < data.height; y++) {
            for (let x = 0; x < data.width; x++) {
              const cell = document.createElement('div');
              cell.id = x + '-' + y;
              cell.className = 'cell';
              arena.appendChild(cell);
            }
          }
        }


        clear(arena, 'cell');
        Array.prototype.slice.call(arena.getElementsByClassName('cell')).forEach(cell => cell.className = 'cell');

        clear(scoreboard, 'score', true);


        const sortPlayersByScore = (player1, player2) => player2[1].score - player1[1].score;

        for (const [playerPath, player] of Object.entries(data.players).sort(sortPlayersByScore)) {
          //println(player);

          // profile image on grid
          const cell = document.getElementById(player.x + '-' + player.y);
          const img = document.createElement('img');
          img.src = player.pic;
          switch (player.direction) {
            case 'N':
              img.className = 'pic direction-n';
              break;
            case 'W':
              img.className = 'pic direction-w';
              break;
            case 'S':
              img.className = 'pic direction-s';
              break;
            case 'E':
              img.className = 'pic direction-e';
              break;
          }
          cell.appendChild(img);

          if (player.wasHit) {
            cell.className = cell.className + " hit";
          }

          // scores
          const scoreLine = document.createElement('div');
          scoreLine.className = 'score';

          const scoreImg = document.createElement('img');
          scoreImg.src = player.pic;
          scoreLine.appendChild(scoreImg);

          const name = document.createElement('span');
          name.innerText = player.name;
          name.className = 'name';
          scoreLine.appendChild(name);

          const score = document.createElement('span');
          score.innerText = player.score;
          score.className = 'num';
          scoreLine.appendChild(score);

          scoreboard.appendChild(scoreLine);
        }

      }
    };

    stringSource.onerror = (error) => {
      // todo: reconnect
      console.log(error);
    };
  }
  else {
    // todo
  }

});


